package com.lcs.star.bpa.worker;

import com.lcs.star.bpa.persistence.WorkPackPrintRepository;
import com.lcs.star.bpa.persistence.WorkPackRepository;
import com.lcs.star.bpa.print.WorkPackPrintRenderer;
import com.lcs.star.bpa.task.TaskService;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Job type {@code generate-workpack-print} (BPA-10) on {@code ServiceTask_GenerateWorkPackPrint}: after scheduling (D4) renders the
 * Workpack print as a PDF, stores it in workpack_print with its sha256 (D29), and sets {@code workpack.assignment_state =
 * AwaitingFile}, which opens the Work Pack for assignment files (D50, D61, G7). Built with the new-job-worker skill.
 *
 * <p>Idempotent on the Work Pack (D29, D65, S7): a Work Pack that already has its print is left as it is and the job completes,
 * checked first, so the sha256 stays the same and an assignment_state that has moved on is never reset. The PDF creation date comes
 * from the injected clock (S1), so even a regenerated print would carry the same hash for the same instant. A Work Pack that is not
 * scheduled (or not Released) is a fault, not a business rejection: the job fails, retries, then ends as an incident (S7). Who:
 * {@code completedBy}, the Production Planner whose completion led here, else the service user (B12): this is a system step.
 */
@Component
public class GenerateWorkPackPrintWorker {

    /** B12: who a worker writes as when no user is in context. */
    static final String SERVICE_USER = "bpa-service";

    private static final Logger LOG = LoggerFactory.getLogger(GenerateWorkPackPrintWorker.class);

    private final WorkPackRepository workPacks;
    private final WorkPackPrintRepository prints;
    private final WorkPackPrintRenderer renderer;
    private final Clock clock;

    public GenerateWorkPackPrintWorker(WorkPackRepository workPacks, WorkPackPrintRepository prints, WorkPackPrintRenderer renderer,
            Clock clock) {
        this.workPacks = workPacks;
        this.prints = prints;
        this.renderer = renderer;
        this.clock = clock;
    }

    @JobWorker(type = "generate-workpack-print", autoComplete = false)
    public void handle(JobClient client, ActivatedJob job) {
        var workPack = workPacks.find(job.getProcessInstanceKey()).orElseThrow(() -> new IllegalStateException(
                "No Work Pack for process instance " + job.getProcessInstanceKey() + " (" + job.getElementId() + ")"));
        if (prints.exists(workPack.id())) {
            LOG.info("generate-workpack-print job {} retried: {} already has its print", job.getKey(), workPack.workpackNumber());
            complete(client, job);
            return;
        }
        var data = prints.loadData(workPack.id()).orElseThrow(() -> new IllegalStateException(
                workPack.workpackNumber() + " is not scheduled, so there is nothing to print yet (D4)"));
        if (!"Released".equals(workPacks.status(workPack.id()).orElse(null))) {
            throw new IllegalStateException(workPack.workpackNumber() + " is not Released, so it cannot be printed (D25)");
        }
        Object completedBy = job.getVariablesAsMap().get(TaskService.COMPLETED_BY);
        String username = completedBy instanceof String s && !s.isBlank() ? s : SERVICE_USER;

        // Microseconds, as the column holds them: the instant in the PDF, the audit text and generated_at are then the same.
        var at = clock.instant().truncatedTo(ChronoUnit.MICROS);
        byte[] pdf = renderer.render(data, at);
        String sha256 = sha256(pdf);

        boolean stored = prints.save(workPack.id(), pdf, sha256, at, username);
        LOG.info("Workpack print of {} {} ({} bytes, sha256 {}, by {}, job {})", workPack.workpackNumber(),
                stored ? "stored" : "already stored", pdf.length, sha256, username, job.getKey());
        complete(client, job);
    }

    private static void complete(JobClient client, ActivatedJob job) {
        client.newCompleteCommand(job.getKey()).send().join();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
