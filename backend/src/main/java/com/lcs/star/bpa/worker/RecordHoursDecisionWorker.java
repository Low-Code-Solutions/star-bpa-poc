package com.lcs.star.bpa.worker;

import com.lcs.star.bpa.persistence.HoursSubmissionRepository;
import com.lcs.star.bpa.persistence.HoursSubmissionRepository.Outcome;
import com.lcs.star.bpa.persistence.HoursSubmissionRepository.WrongStage;
import com.lcs.star.bpa.task.TaskService;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Job type {@code record-hours-decision} (BPA-53, BPA-56; E2-05, E2-06; 09 S7, ADR 0007), shared by the three approval steps of
 * Process_PPC_TimesheetToProgress; the {@code step} task header says which.
 * <ul>
 * <li>Timekeeper (BPA-53): Validated or Returned.</li>
 * <li>ProductionSupervisor (BPA-56): Approved, Returned or Rejected; the decider must not be the user who sent the hours (segregation of duties, data
 * model §13.2.2 "worker-checked", ASSUMPTION for hours). The task API refuses the same case first (HoursSegregationCheck), so this is the backstop.</li>
 * <li>Ppc: BPA-57. Until then a job for it fails with no retries, so Operate shows an incident that says so.</li>
 * </ul>
 * Re-run key: the user task's key ({@code completedTaskKey}, hours_decision.task_key UNIQUE, D65). The decision and the reason were checked by the task API
 * (09 S14); anything missing, unknown for the step, a submission at another stage, or a segregation breach fails the job with no retries (a retry cannot
 * mend it). Who decided: {@code completedBy} (D63). The job completes without output: the gateway reads {@code hoursDecision}, which the completion set.
 */
@Component
public class RecordHoursDecisionWorker {

    /** ASSUMPTION text (BPA-56 plan Q1); the task API answers 403 with the same text. */
    public static String segregationMessage(String username) {
        return "Segregation of duties: " + username + " sent these hours and cannot decide on them";
    }

    private static final Map<String, Set<String>> DECISIONS = Map.of(
            "Timekeeper", Set.of("Validated", "Returned"),
            "ProductionSupervisor", Set.of("Approved", "Returned", "Rejected"));

    private static final Logger LOG = LoggerFactory.getLogger(RecordHoursDecisionWorker.class);

    private final HoursSubmissionRepository submissions;

    public RecordHoursDecisionWorker(HoursSubmissionRepository submissions) {
        this.submissions = submissions;
    }

    @JobWorker(type = "record-hours-decision", autoComplete = false)
    public void handle(JobClient client, ActivatedJob job) {
        String step = job.getCustomHeaders().get("step");
        if (!DECISIONS.containsKey(step)) {
            fail(client, job, "Step " + step + " of record-hours-decision is not built yet (BPA-57)");
            return;
        }
        Map<String, Object> variables = job.getVariablesAsMap();
        if (!(variables.get("hoursSubmissionId") instanceof String submissionText) || submissionText.isBlank()) {
            throw new IllegalStateException("No hoursSubmissionId on the decision (job " + job.getKey() + ")");
        }
        UUID submissionId = UUID.fromString(submissionText);
        long taskKey = taskKey(variables.get(TaskService.COMPLETED_TASK_KEY));
        if (!(variables.get(TaskService.COMPLETED_BY) instanceof String username) || username.isBlank()) {
            throw new IllegalStateException("No completedBy on the decision (" + job.getElementId() + ", job " + job.getKey() + ")");
        }
        String decision = String.valueOf(variables.get("hoursDecision"));
        if (!DECISIONS.get(step).contains(decision)) {
            fail(client, job, "Unknown hours decision " + decision + " at step " + step);
            return;
        }
        String reason = variables.get("hoursReason") instanceof String text ? text : null;
        if (("Returned".equals(decision) || "Rejected".equals(decision)) && (reason == null || reason.isBlank())) {
            fail(client, job, "A " + decision + " decision has no reason");
            return;
        }
        if (!"Timekeeper".equals(step) && submissions.submittedBy(submissionId).map(username::equals).orElse(false)) {
            fail(client, job, segregationMessage(username));
            return;
        }
        Outcome outcome;
        try {
            outcome = submissions.decide(step, submissionId, taskKey, decision, reason, username);
        } catch (WrongStage e) {
            fail(client, job, e.getMessage());
            return;
        }
        LOG.info("{} decision on submission {}: {} by {} (task {}, job {})", step, submissionId, outcome, username, taskKey, job.getKey());
        client.newCompleteCommand(job.getKey()).send().join();
    }

    /** No retries: the input cannot change on a retry. Operate shows the message as the incident. */
    private static void fail(JobClient client, ActivatedJob job, String message) {
        LOG.warn("record-hours-decision job {} failed: {}", job.getKey(), message);
        client.newFailCommand(job.getKey()).retries(0).errorMessage(message).send().join();
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
