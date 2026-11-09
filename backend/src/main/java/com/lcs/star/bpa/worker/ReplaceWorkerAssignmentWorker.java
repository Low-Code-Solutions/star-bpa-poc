package com.lcs.star.bpa.worker;

import com.lcs.star.bpa.assignment.WorkerReplacementService;
import com.lcs.star.bpa.assignment.WorkerReplacementService.NotReplacingException;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Job type {@code replace-worker-assignment} on {@code ServiceTask_ReplaceAssignment} of Process_PPC_WorkerReassignment (BPA-13; D35, D36, D38, D61,
 * D71, D72). The upload endpoint sets the Work Pack to Replacing and starts the process with the message {@code AssignmentFileReplaced}, carrying
 * {@code workpackNumber} and {@code assignmentFileId} (BPA-12). The job replaces the Work Pack's Worker list from that file's rows (see
 * {@link WorkerReplacementService}) and completes with {@code replacementApplied}, which Gateway_IsReplacementApplied reads: true ends at
 * EndEvent_AssignmentReplaced, false at EndEvent_ReplacementNotApplied. Rejected rows are a result, not an error. This instance is not the Work
 * Pack's own, so the Work Pack is found by its number. A Work Pack that is not Replacing, or a message without its two variables, fails the job with no
 * retries and says why, so Operate shows an incident and nothing is saved. Retries and backoff are Camunda's defaults (S7, ADR 0007).
 */
@Component
public class ReplaceWorkerAssignmentWorker {

    private static final Logger LOG = LoggerFactory.getLogger(ReplaceWorkerAssignmentWorker.class);

    private final WorkerReplacementService replacements;

    public ReplaceWorkerAssignmentWorker(WorkerReplacementService replacements) {
        this.replacements = replacements;
    }

    @JobWorker(type = "replace-worker-assignment", autoComplete = false)
    public void handle(JobClient client, ActivatedJob job) {
        Map<String, Object> variables = job.getVariablesAsMap();
        Object number = variables.get("workpackNumber");
        UUID fileId = fileId(variables.get("assignmentFileId"));
        if (!(number instanceof String workpackNumber) || workpackNumber.isBlank() || fileId == null) {
            fail(client, job, "the message carried no valid workpackNumber and assignmentFileId");
            return;
        }
        boolean applied;
        try {
            applied = replacements.replace(workpackNumber, fileId);
        } catch (NotReplacingException e) {
            fail(client, job, e.getMessage());
            return;
        }
        LOG.info("replace-worker-assignment job {}: {} replacement from file {} {}", job.getKey(), workpackNumber, fileId, applied ? "applied" : "not applied");
        client.newCompleteCommand(job.getKey()).variables(Map.of("replacementApplied", applied)).send().join();
    }

    private static UUID fileId(Object value) {
        try {
            return value instanceof String s ? UUID.fromString(s) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void fail(JobClient client, ActivatedJob job, String message) {
        LOG.warn("replace-worker-assignment job {} (instance {}): {}", job.getKey(), job.getProcessInstanceKey(), message);
        client.newFailCommand(job.getKey()).retries(0).errorMessage(message).send().join();
    }
}
