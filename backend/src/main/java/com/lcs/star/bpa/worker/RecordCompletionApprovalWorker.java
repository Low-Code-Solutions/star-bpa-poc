package com.lcs.star.bpa.worker;

import com.lcs.star.bpa.persistence.ProgressPeriodRepository;
import com.lcs.star.bpa.persistence.ProgressPeriodRepository.Period;
import com.lcs.star.bpa.progress.CompletionApprovalService;
import com.lcs.star.bpa.progress.CompletionApprovalService.Approval;
import com.lcs.star.bpa.task.TaskService;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Job type {@code record-completion-approval} on {@code ServiceTask_RecordCompletionApproval} (BPA-60; E2-10; plan Q7, ADR 0006 and 0007). The task API has
 * checked the approvals before the task completed (the week before locked, every Work Pack covered, decimals, range, remarks); this step writes them to
 * {@code workpack_progress} for the week of the instance's progress period, all or nothing, with {@code completedBy} as who approved
 * ({@link CompletionApprovalService}). Re-run key: a row that already holds the same values is left as it is, so {@code approved_at} is kept and nothing is
 * audited twice. A payload that is not understood, or a week with no progress period, fails the job with no retries (the instance was completed some other
 * way). BPA-61's {@code prepare-progress-check} reads {@code approved_pct} and {@code previous_pct} from the table; there is no output variable.
 */
@Component
public class RecordCompletionApprovalWorker {

    private static final Logger LOG = LoggerFactory.getLogger(RecordCompletionApprovalWorker.class);

    private final CompletionApprovalService service;
    private final ProgressPeriodRepository periods;

    public RecordCompletionApprovalWorker(CompletionApprovalService service, ProgressPeriodRepository periods) {
        this.service = service;
        this.periods = periods;
    }

    @JobWorker(type = "record-completion-approval", autoComplete = false)
    public void handle(JobClient client, ActivatedJob job) {
        Map<String, Object> variables = job.getVariablesAsMap();
        String username = variables.get(TaskService.COMPLETED_BY) instanceof String who && !who.isBlank() ? who : OpenProgressPeriodWorker.SERVICE_USER;
        Optional<Period> period = periods.ofInstance(job.getProcessInstanceKey());
        if (period.isEmpty()) {
            fail(client, job, "The week of this instance has no progress period");
            return;
        }
        try {
            List<Approval> approvals = CompletionApprovalService.parse(variables.get("completionApprovals"));
            if (approvals.isEmpty()) {
                throw new IllegalArgumentException("No completionApprovals on the completion approval");
            }
            service.record(period.get(), approvals, username);
            LOG.info("record-completion-approval job {}: {} Work Pack(s) approved for week ending {} by {}", job.getKey(), approvals.size(),
                    period.get().weekEnding(), username);
        } catch (IllegalArgumentException e) {
            fail(client, job, e.getMessage());
            return;
        }
        client.newCompleteCommand(job.getKey()).send().join();
    }

    private static void fail(JobClient client, ActivatedJob job, String message) {
        LOG.warn("record-completion-approval job {}: {}", job.getKey(), message);
        client.newFailCommand(job.getKey()).retries(0).errorMessage(message).send().join();
    }
}
