package com.lcs.star.bpa.worker;

import com.lcs.star.bpa.persistence.HoursSubmissionRepository;
import com.lcs.star.bpa.persistence.HoursSubmissionRepository.Outcome;
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
 * Job type {@code record-hours-decision} (BPA-53; E2-05; 09 S7, ADR 0007), shared by the three approval steps of Process_PPC_TimesheetToProgress; the
 * {@code step} task header says which. BPA-53 builds the Timekeeper's step: Validated moves the submission to stage ProductionSupervisor; Returned
 * returns its lines to the Supervisor and closes the submission (outcome Returned). Production Supervisor and PPC steps belong to BPA-56 and BPA-57:
 * a job for them fails with no retries, so Operate shows an incident that says so. Re-run key: the user task's key ({@code completedTaskKey},
 * hours_decision.task_key UNIQUE, D65). The decision and the reason were checked by the task API (09 S14); anything missing or unknown is a fault.
 * Who decided: {@code completedBy} (D63), never the service user. The job completes without output: the gateway reads {@code hoursDecision}, which
 * the task completion already set.
 */
@Component
public class RecordHoursDecisionWorker {

    private static final Logger LOG = LoggerFactory.getLogger(RecordHoursDecisionWorker.class);

    private final HoursSubmissionRepository submissions;

    public RecordHoursDecisionWorker(HoursSubmissionRepository submissions) {
        this.submissions = submissions;
    }

    @JobWorker(type = "record-hours-decision", autoComplete = false)
    public void handle(JobClient client, ActivatedJob job) {
        String step = job.getCustomHeaders().get("step");
        if (!"Timekeeper".equals(step)) {
            // no retries: a later story builds this step (BPA-56, BPA-57); an incident says so instead of a silent wrong write
            client.newFailCommand(job.getKey()).retries(0).errorMessage("Step " + step + " of record-hours-decision is not built yet (BPA-56, BPA-57)").send().join();
            return;
        }
        Map<String, Object> variables = job.getVariablesAsMap();
        if (!(variables.get("hoursSubmissionId") instanceof String submissionId) || submissionId.isBlank()) {
            throw new IllegalStateException("No hoursSubmissionId on the decision (job " + job.getKey() + ")");
        }
        long taskKey = taskKey(variables.get(TaskService.COMPLETED_TASK_KEY));
        if (!(variables.get(TaskService.COMPLETED_BY) instanceof String username) || username.isBlank()) {
            throw new IllegalStateException("No completedBy on the decision (" + job.getElementId() + ", job " + job.getKey() + ")");
        }
        boolean validated = switch (String.valueOf(variables.get("hoursDecision"))) {
            case "Validated" -> true;
            case "Returned" -> false;
            default -> throw new IllegalStateException("Unknown hours decision " + variables.get("hoursDecision"));
        };
        String reason = variables.get("hoursReason") instanceof String text ? text : null;
        if (!validated && (reason == null || reason.isBlank())) {
            throw new IllegalStateException("A returned submission has no reason (job " + job.getKey() + ")");
        }
        Outcome outcome = submissions.decideAsTimekeeper(UUID.fromString(submissionId), taskKey, validated, reason, username);
        LOG.info("Timekeeper decision on submission {}: {} by {} (task {}, job {})", submissionId, outcome, username, taskKey, job.getKey());
        client.newCompleteCommand(job.getKey()).send().join();
    }

    /** The key arrives as the engine's string, or as a number if a client library turned it into one. */
    private static long taskKey(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && text.matches("\\d{1,19}")) {
            return Long.parseLong(text);
        }
        throw new IllegalStateException("No completedTaskKey on the decision: " + value);
    }
}
