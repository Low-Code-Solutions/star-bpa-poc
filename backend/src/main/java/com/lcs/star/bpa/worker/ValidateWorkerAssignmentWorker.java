package com.lcs.star.bpa.worker;

import com.lcs.star.bpa.assignment.WorkerAssignmentService;
import com.lcs.star.bpa.assignment.WorkerAssignmentService.NotAwaitingFileException;
import com.lcs.star.bpa.persistence.WorkPackRepository;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Job type {@code validate-worker-assignment} on {@code ServiceTask_ValidateWorkerAssignment} (BPA-22; D5, D14, D32, D49, D59, D61, C11).
 * BPA-12 routes an uploaded file to the instance waiting at IntermediateCatchEvent_AssignmentFileReceived and sends the file's id as the
 * variable {@code assignmentFileId}. This step checks the file's rows for this instance's Work Pack, saves the accepted Workers, writes every
 * row's outcome, sets assignment_state (see {@link WorkerAssignmentService}) and completes with {@code assignedWorkerCount}, which
 * Gateway_HasAssignedWorkers reads: above 0 the instance goes on to the Hour Journal Lines (BPA-14), otherwise back to wait for a corrected file.
 *
 * <p>Rejected rows are results, not errors: the model has no boundary event here, only the gateway on the count. A Work Pack that is not
 * AwaitingFile fails the job with no retries and says why, so Operate shows an incident and nothing is saved (as BPA-23 did for D8).
 * Retries and backoff are Camunda's defaults (S7, ADR 0007).
 */
@Component
public class ValidateWorkerAssignmentWorker {

    private static final Logger LOG = LoggerFactory.getLogger(ValidateWorkerAssignmentWorker.class);

    private final WorkPackRepository workPacks;
    private final WorkerAssignmentService assignments;

    public ValidateWorkerAssignmentWorker(WorkPackRepository workPacks, WorkerAssignmentService assignments) {
        this.workPacks = workPacks;
        this.assignments = assignments;
    }

    @JobWorker(type = "validate-worker-assignment", autoComplete = false)
    public void handle(JobClient client, ActivatedJob job) {
        var workPack = workPacks.find(job.getProcessInstanceKey()).orElseThrow(() -> new IllegalStateException(
                "No Work Pack for process instance " + job.getProcessInstanceKey() + " (" + job.getElementId() + ")"));
        UUID fileId = fileId(job);
        if (fileId == null) {
            fail(client, job, workPack.workpackNumber() + ": the message carried no valid assignmentFileId");
            return;
        }
        int assigned;
        try {
            assigned = assignments.validate(workPack.id(), workPack.workpackNumber(), fileId);
        } catch (NotAwaitingFileException e) {
            fail(client, job, e.getMessage());
            return;
        }
        LOG.info("validate-worker-assignment job {}: {} has {} Worker(s) from file {}", job.getKey(), workPack.workpackNumber(), assigned, fileId);
        client.newCompleteCommand(job.getKey()).variables(Map.of("assignedWorkerCount", assigned)).send().join();
    }

    private static UUID fileId(ActivatedJob job) {
        Object value = job.getVariablesAsMap().get("assignmentFileId");
        try {
            return value instanceof String s ? UUID.fromString(s) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void fail(JobClient client, ActivatedJob job, String message) {
        LOG.warn("validate-worker-assignment job {} (instance {}): {}", job.getKey(), job.getProcessInstanceKey(), message);
        client.newFailCommand(job.getKey()).retries(0).errorMessage(message).send().join();
    }
}
