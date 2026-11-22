package com.lcs.star.bpa.worker;

import com.lcs.star.bpa.persistence.ProgressPeriodRepository.Period;
import com.lcs.star.bpa.progress.ProgressCheckService;
import com.lcs.star.bpa.progress.WeekDecisionService;
import com.lcs.star.bpa.task.TaskService;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Job type {@code record-week-decision} on {@code ServiceTask_RecordWeekDecision} (BPA-66; E2-19; plan Q1; ADR 0006 and 0007). The task API has checked the decision before
 * {@code UserTask_ApproveWeeklyProgress} completed (the decision, the target and the reason; the 409 for the approver of the %); this step writes it for the week of the
 * instance's progress period ({@link WeekDecisionService}), with {@code completedBy} as the Project Manager. Re-run key: the values, so a retried job writes nothing again.
 * A week with no period, a Locked week or a decision the checks refuse fails the job with no retries (the instance was completed some other way). No output variable:
 * {@code Gateway_IsWeekApproved} reads {@code weekDecision} and {@code weekChangeTarget} as the task set them.
 */
@Component
public class RecordWeekDecisionWorker {

    private static final Logger LOG = LoggerFactory.getLogger(RecordWeekDecisionWorker.class);

    private final ProgressCheckService periods;
    private final WeekDecisionService service;

    public RecordWeekDecisionWorker(ProgressCheckService periods, WeekDecisionService service) {
        this.periods = periods;
        this.service = service;
    }

    @JobWorker(type = "record-week-decision", autoComplete = false)
    public void handle(JobClient client, ActivatedJob job) {
        Map<String, Object> variables = job.getVariablesAsMap();
        String username = variables.get(TaskService.COMPLETED_BY) instanceof String who && !who.isBlank() ? who : OpenProgressPeriodWorker.SERVICE_USER;
        Optional<Period> period = periods.periodOf(job.getProcessInstanceKey(), variables.get("progressPeriodId"));
        if (period.isEmpty()) {
            fail(client, job, WeekDecisionService.NO_PERIOD);
            return;
        }
        try {
            service.record(period.get().id(), text(variables.get("weekDecision")), text(variables.get("weekChangeTarget")), text(variables.get("weekDecisionReason")), username);
            LOG.info("record-week-decision job {}: {} for week ending {} by {}", job.getKey(), variables.get("weekDecision"), period.get().weekEnding(), username);
        } catch (IllegalArgumentException e) {
            fail(client, job, e.getMessage());
            return;
        }
        client.newCompleteCommand(job.getKey()).send().join();
    }

    private static String text(Object value) {
        return value instanceof String s ? s : null;
    }

    private static void fail(JobClient client, ActivatedJob job, String message) {
        LOG.warn("record-week-decision job {}: {}", job.getKey(), message);
        client.newFailCommand(job.getKey()).retries(0).errorMessage(message).send().join();
    }
}
