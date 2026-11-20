package com.lcs.star.bpa.worker;

import com.lcs.star.bpa.persistence.ProgressPeriodRepository.Period;
import com.lcs.star.bpa.progress.EarnedManhoursService;
import com.lcs.star.bpa.progress.ProgressCheckService;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Job type {@code calculate-earned-manhours} on {@code ServiceTask_CalculateEarnedManhours} (BPA-62; E2-13; ADR 0006 and 0007). Stores each activity's progress %
 * and Earned man-hours for the week of the instance's progress period ({@link EarnedManhoursService#calculate}); no process variable is set. Re-run key: the
 * (activity, week) row and its values, so a retried job gives the same rows and writes nothing. A week with no progress period or a Locked week fails the job
 * with no retries, as the BPA-61 workers do: this is not a user input error, so there is no BPMN error.
 */
@Component
public class CalculateEarnedManhoursWorker {

    private static final Logger LOG = LoggerFactory.getLogger(CalculateEarnedManhoursWorker.class);

    private final ProgressCheckService periods;
    private final EarnedManhoursService service;

    public CalculateEarnedManhoursWorker(ProgressCheckService periods, EarnedManhoursService service) {
        this.periods = periods;
        this.service = service;
    }

    @JobWorker(type = "calculate-earned-manhours", autoComplete = false)
    public void handle(JobClient client, ActivatedJob job) {
        Optional<Period> period = periods.periodOf(job.getProcessInstanceKey(), job.getVariablesAsMap().get("progressPeriodId"));
        if (period.isEmpty()) {
            fail(client, job, "The week of this instance has no progress period");
            return;
        }
        try {
            service.calculate(period.get(), OpenProgressPeriodWorker.SERVICE_USER);
        } catch (IllegalArgumentException e) {
            fail(client, job, e.getMessage());
            return;
        }
        LOG.info("calculate-earned-manhours job {}: week ending {} calculated", job.getKey(), period.get().weekEnding());
        client.newCompleteCommand(job.getKey()).variables(Map.of()).send().join();
    }

    private static void fail(JobClient client, ActivatedJob job, String message) {
        LOG.warn("calculate-earned-manhours job {}: {}", job.getKey(), message);
        client.newFailCommand(job.getKey()).retries(0).errorMessage(message).send().join();
    }
}
