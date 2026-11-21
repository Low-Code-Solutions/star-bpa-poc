package com.lcs.star.bpa.worker;

import com.lcs.star.bpa.persistence.ProgressPeriodRepository.Period;
import com.lcs.star.bpa.progress.ProgressCheckService;
import com.lcs.star.bpa.progress.VarianceCheckService;
import com.lcs.star.bpa.progress.VarianceCheckService.Check;
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
 * Job type {@code prepare-variance-check} on {@code ServiceTask_PrepareVarianceCheck} (BPA-64; E2-14; ADR 0006 and 0007). Builds the process variable
 * {@code varianceChecks}, one entry per CBS that has Approved hours up to the week ending or a Current Forecast ({@link VarianceCheckService}): {@code cbsCodeRef},
 * {@code actualManhours}, {@code currentForecastManhours}, and for BPA-65's cost decision {@code revisedBudgetCost} and {@code actualCost} (null when the CBS has none, so
 * the cost decision gives NO_BUDGET and does not fail). Lines with no CBS never become an entry (D40). Writes nothing, so a retried job gives the same list. A week with no
 * progress period fails the job with no retries, as the BPA-61 and BPA-62 workers do (not user input, so no BPMN error).
 */
@Component
public class PrepareVarianceCheckWorker {

    private static final Logger LOG = LoggerFactory.getLogger(PrepareVarianceCheckWorker.class);

    private final ProgressCheckService periods;
    private final VarianceCheckService service;

    public PrepareVarianceCheckWorker(ProgressCheckService periods, VarianceCheckService service) {
        this.periods = periods;
        this.service = service;
    }

    @JobWorker(type = "prepare-variance-check", autoComplete = false)
    public void handle(JobClient client, ActivatedJob job) {
        Optional<Period> period = periods.periodOf(job.getProcessInstanceKey(), job.getVariablesAsMap().get("progressPeriodId"));
        if (period.isEmpty()) {
            LOG.warn("prepare-variance-check job {}: the week of this instance has no progress period", job.getKey());
            client.newFailCommand(job.getKey()).retries(0).errorMessage("The week of this instance has no progress period").send().join();
            return;
        }
        List<Map<String, Object>> checks = service.checks(period.get().weekEnding()).stream().map(Check::asVariable).toList();
        LOG.info("prepare-variance-check job {}: {} CBS to check for week ending {}", job.getKey(), checks.size(), period.get().weekEnding());
        client.newCompleteCommand(job.getKey()).variables(Map.of("varianceChecks", checks)).send().join();
    }
}
