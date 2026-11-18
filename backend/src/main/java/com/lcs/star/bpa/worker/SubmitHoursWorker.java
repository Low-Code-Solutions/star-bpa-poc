package com.lcs.star.bpa.worker;

import com.lcs.star.bpa.persistence.HoursSubmissionRepository;
import com.lcs.star.bpa.persistence.HoursSubmissionRepository.Sent;
import com.lcs.star.bpa.task.TaskService;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Job type {@code submit-hours} on {@code ServiceTask_SubmitHours} (BPA-53; E2-05; 09 S7, ADR 0007). Runs after the budget check passed: creates the
 * hours_submission (stage Timekeeper) for the Work Pack, sets its Draft and Returned lines Submitted and links them (audit rows per column, D64), and
 * completes with {@code hoursSubmissionId}, which UserTask_ValidateHours reads. Re-run key: process instance key and round number, where the round is 1
 * plus the instance's Closed submissions, so a retried job finds the same open round and changes nothing (the output is the same id). Who sent:
 * {@code completedBy}, the Supervisor who completed Book hours (D63); {@code bpa-service} when the variable is missing (an instance started by hand).
 * Retries and backoff are Camunda's defaults (S7); a missing workpackId is a fault, not a BPMN error.
 */
@Component
public class SubmitHoursWorker {

    static final String SERVICE_USER = "bpa-service";

    private static final Logger LOG = LoggerFactory.getLogger(SubmitHoursWorker.class);

    private final HoursSubmissionRepository submissions;

    public SubmitHoursWorker(HoursSubmissionRepository submissions) {
        this.submissions = submissions;
    }

    @JobWorker(type = "submit-hours", autoComplete = false)
    public void handle(JobClient client, ActivatedJob job) {
        Map<String, Object> variables = job.getVariablesAsMap();
        if (!(variables.get("workpackId") instanceof String workpackId) || workpackId.isBlank()) {
            throw new IllegalStateException("No workpackId on the hours to send (job " + job.getKey() + ")");
        }
        String username = variables.get(TaskService.COMPLETED_BY) instanceof String who && !who.isBlank() ? who : SERVICE_USER;
        Sent sent = submissions.send(job.getProcessInstanceKey(), UUID.fromString(workpackId), username);
        LOG.info("submit-hours job {}: submission {} round {} with {} line(s) newly sent by {}", job.getKey(), sent.submissionId(), sent.roundNo(), sent.lineCount(),
                username);
        // hoursDecision and hoursReason are cleared: the Supervisor sees "Hours returned" only for the return that sent the hours back (BPA-53 review)
        Map<String, Object> output = new java.util.HashMap<>();
        output.put("hoursSubmissionId", sent.submissionId().toString());
        output.put("hoursDecision", null);
        output.put("hoursReason", null);
        client.newCompleteCommand(job.getKey()).variables(output).send().join();
    }
}
