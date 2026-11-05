package com.lcs.star.bpa.worker;

import com.lcs.star.bpa.persistence.ReleaseSignOffRepository;
import com.lcs.star.bpa.persistence.WorkPackRepository;
import com.lcs.star.bpa.task.TaskService;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Job type {@code record-release-signoff} (BPA-9) on {@code ServiceTask_RecordSignOff}: stores the Project Engineer's decision in
 * release_signoff and, on approval, sets the Work Pack Released and locks its Header (D8). On a decline it sets
 * {@code confirmMessage} to "Declined by Project Engineer: <reason>", which the Confirm Workpack screen shows (D3, D51).
 *
 * <p>Built with the new-job-worker skill. Idempotent on the user task's key (D65, S7): the task API puts it on the process as
 * {@code completedTaskKey}, and release_signoff.task_key is UNIQUE, so a retried job records the decision once. The decision and
 * the reason come from the task completion, already checked by the task API (09 S14), so there is no input to reject and no BPMN
 * error; anything missing is a fault: the job fails, retries, then an incident (S7). Who: {@code completedBy} (D63), never the
 * service user, because the sign-off is the Project Engineer's.
 */
@Component
public class RecordSignOffWorker {

    static final String DECLINED_PREFIX = "Declined by Project Engineer: ";

    private static final Logger LOG = LoggerFactory.getLogger(RecordSignOffWorker.class);

    private final WorkPackRepository workPacks;
    private final ReleaseSignOffRepository signOffs;

    public RecordSignOffWorker(WorkPackRepository workPacks, ReleaseSignOffRepository signOffs) {
        this.workPacks = workPacks;
        this.signOffs = signOffs;
    }

    @JobWorker(type = "record-release-signoff", autoComplete = false)
    public void handle(JobClient client, ActivatedJob job) {
        Map<String, Object> variables = job.getVariablesAsMap();
        long taskKey = taskKey(variables.get(TaskService.COMPLETED_TASK_KEY));
        if (!(variables.get(TaskService.COMPLETED_BY) instanceof String username) || username.isBlank()) {
            throw new IllegalStateException("No completedBy on the sign-off (" + job.getElementId() + ", job " + job.getKey() + ")");
        }
        boolean approved = switch (String.valueOf(variables.get("signOffDecision"))) {
            case "Approved" -> true;
            case "Declined" -> false;
            default -> throw new IllegalStateException("Unknown sign-off decision " + variables.get("signOffDecision"));
        };
        String reason = variables.get("declineReason") instanceof String text ? text : null;
        if (!approved && (reason == null || reason.isBlank())) {
            throw new IllegalStateException("A declined sign-off has no reason (job " + job.getKey() + ")");
        }
        var workPack = workPacks.find(job.getProcessInstanceKey()).orElseThrow(() -> new IllegalStateException(
                "No Work Pack for process instance " + job.getProcessInstanceKey() + " (" + job.getElementId() + ")"));

        var outcome = signOffs.record(workPack.id(), taskKey, approved, reason, username);

        LOG.info("Sign-off of {} {} by {} (task {}, job {})", workPack.workpackNumber(), outcome, username, taskKey, job.getKey());
        var complete = client.newCompleteCommand(job.getKey());
        // The same output on a re-run as on the first run: it comes from the job's variables, not from what was written now.
        (approved ? complete : complete.variables(Map.of("confirmMessage", DECLINED_PREFIX + reason.trim()))).send().join();
    }

    /** The key arrives as the engine's string, or as a number if a client library turned it into one. */
    private static long taskKey(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && text.matches("\\d{1,19}")) {
            return Long.parseLong(text);
        }
        throw new IllegalStateException("No completedTaskKey on the sign-off: " + value);
    }
}
