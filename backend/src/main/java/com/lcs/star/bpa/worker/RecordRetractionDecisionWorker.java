package com.lcs.star.bpa.worker;

import com.lcs.star.bpa.persistence.ProgressPeriodRepository;
import com.lcs.star.bpa.persistence.ProgressPeriodRepository.Period;
import com.lcs.star.bpa.progress.RetractionDecisionService;
import com.lcs.star.bpa.progress.RetractionDecisionService.Decision;
import com.lcs.star.bpa.task.TaskService;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Job type {@code record-retraction-decision} on {@code ServiceTask_RecordRetractionDecision} (BPA-63; E2-12; ADR 0006 and 0007). The task API has checked the
 * decisions before the task completed (every waiting Work Pack covered, a reason for a decline, not the requester); this step writes them to
 * {@code progress_retraction} ({@link RetractionDecisionService#decide}) with {@code completedBy} as who decided and {@code completedTaskKey} as the re-run key,
 * and sets {@code retractionOutcome}: "Approved" only when every Work Pack is Approved, else "Declined". A declined week also clears {@code validationMessages}, so
 * BPA-61's "a retraction is needed" lines do not show beside the decline (ASSUMPTION, plan Q5). Anything the checks refuse fails the job with no retries (the
 * instance was completed some other way), as the BPA-56 and BPA-60 workers do: it is not user input, so there is no BPMN error.
 */
@Component
public class RecordRetractionDecisionWorker {

    private static final Logger LOG = LoggerFactory.getLogger(RecordRetractionDecisionWorker.class);

    private final RetractionDecisionService service;
    private final ProgressPeriodRepository periods;

    public RecordRetractionDecisionWorker(RetractionDecisionService service, ProgressPeriodRepository periods) {
        this.service = service;
        this.periods = periods;
    }

    @JobWorker(type = "record-retraction-decision", autoComplete = false)
    public void handle(JobClient client, ActivatedJob job) {
        Map<String, Object> variables = job.getVariablesAsMap();
        String username = variables.get(TaskService.COMPLETED_BY) instanceof String who && !who.isBlank() ? who : OpenProgressPeriodWorker.SERVICE_USER;
        Optional<Period> period = periods.ofInstance(job.getProcessInstanceKey());
        if (period.isEmpty()) {
            fail(client, job, "The week of this instance has no progress period");
            return;
        }
        Object key = variables.get(TaskService.COMPLETED_TASK_KEY);
        if (key == null) {
            fail(client, job, "No completedTaskKey on the retraction decision");
            return;
        }
        String outcome;
        try {
            List<Decision> decisions = RetractionDecisionService.parse(variables.get("retractionDecisions"));
            if (decisions.isEmpty()) {
                throw new IllegalArgumentException("No retractionDecisions on the retraction decision");
            }
            outcome = service.decide(period.get(), decisions, username, Long.parseLong(String.valueOf(key)));
        } catch (IllegalArgumentException e) {
            fail(client, job, e.getMessage());
            return;
        }
        Map<String, Object> out = new HashMap<>();
        out.put("retractionOutcome", outcome);
        if ("Declined".equals(outcome)) {
            out.put("validationMessages", List.of());
        }
        LOG.info("record-retraction-decision job {}: week ending {} decided by {}: {}", job.getKey(), period.get().weekEnding(), username, outcome);
        client.newCompleteCommand(job.getKey()).variables(out).send().join();
    }

    private static void fail(JobClient client, ActivatedJob job, String message) {
        LOG.warn("record-retraction-decision job {}: {}", job.getKey(), message);
        client.newFailCommand(job.getKey()).retries(0).errorMessage(message).send().join();
    }
}
