package com.lcs.star.bpa.worker;

import com.lcs.star.bpa.persistence.ProgressPeriodRepository.Period;
import com.lcs.star.bpa.progress.ProgressCheckService;
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
 * Job type {@code prepare-progress-check} on {@code ServiceTask_PrepareProgressCheck} (BPA-61; E2-11; ADR 0006 and 0007). Builds {@code progressChecks}, one
 * entry per Work Pack that PPC approved a % for in the week of the instance's progress period: {@code workpackNumber}, {@code newPct}, {@code previousPct}
 * (the last locked week's approved %, 0 when none) and {@code retractionApproved} (true only for an Approved retraction of exactly {@code newPct}, decision
 * (a); {@link ProgressCheckService#checks}). It only reads, so a retried job gives the same list. A week with nothing approved fails the job with no retries
 * ("No approved completion % for the week", an ASSUMPTION: BPA-60's all-or-nothing approval makes it unreachable through the screen), as does a week with no
 * progress period.
 */
@Component
public class PrepareProgressCheckWorker {

    private static final Logger LOG = LoggerFactory.getLogger(PrepareProgressCheckWorker.class);

    private final ProgressCheckService service;

    public PrepareProgressCheckWorker(ProgressCheckService service) {
        this.service = service;
    }

    @JobWorker(type = "prepare-progress-check", autoComplete = false)
    public void handle(JobClient client, ActivatedJob job) {
        Map<String, Object> variables = job.getVariablesAsMap();
        Optional<Period> period = service.periodOf(job.getProcessInstanceKey(), variables.get("progressPeriodId"));
        if (period.isEmpty()) {
            fail(client, job, "The week of this instance has no progress period");
            return;
        }
        List<Map<String, Object>> checks = service.checks(period.get());
        if (checks.isEmpty()) {
            fail(client, job, "No approved completion % for the week");
            return;
        }
        LOG.info("prepare-progress-check job {}: {} Work Pack(s) to check for week ending {}", job.getKey(), checks.size(), period.get().weekEnding());
        client.newCompleteCommand(job.getKey()).variables(Map.of("progressChecks", checks)).send().join();
    }

    private static void fail(JobClient client, ActivatedJob job, String message) {
        LOG.warn("prepare-progress-check job {}: {}", job.getKey(), message);
        client.newFailCommand(job.getKey()).retries(0).errorMessage(message).send().join();
    }
}
