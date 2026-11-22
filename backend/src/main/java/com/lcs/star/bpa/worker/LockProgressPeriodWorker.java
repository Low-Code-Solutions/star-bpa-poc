package com.lcs.star.bpa.worker;

import com.lcs.star.bpa.persistence.ProgressPeriodRepository.Period;
import com.lcs.star.bpa.progress.ProgressCheckService;
import com.lcs.star.bpa.progress.ProgressLockService;
import com.lcs.star.bpa.progress.WeekDecisionService;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Job type {@code lock-progress-period} on {@code ServiceTask_LockProgressPeriod} (BPA-67; E2-16; ADR 0007). Locks the week of the instance's progress period
 * ({@link ProgressLockService}): status Locked and {@code locked_at}, after the Work Packs were completed. Re-run key: the status (a Locked period is not written again). A week
 * with no period fails the job with no retries. No output variable; the process then ends at {@code EndEvent_PeriodLocked}.
 */
@Component
public class LockProgressPeriodWorker {

    private static final Logger LOG = LoggerFactory.getLogger(LockProgressPeriodWorker.class);

    private final ProgressCheckService periods;
    private final ProgressLockService service;

    public LockProgressPeriodWorker(ProgressCheckService periods, ProgressLockService service) {
        this.periods = periods;
        this.service = service;
    }

    @JobWorker(type = "lock-progress-period", autoComplete = false)
    public void handle(JobClient client, ActivatedJob job) {
        Map<String, Object> variables = job.getVariablesAsMap();
        Optional<Period> period = periods.periodOf(job.getProcessInstanceKey(), variables.get("progressPeriodId"));
        if (period.isEmpty()) {
            LOG.warn("lock-progress-period job {}: {}", job.getKey(), WeekDecisionService.NO_PERIOD);
            client.newFailCommand(job.getKey()).retries(0).errorMessage(WeekDecisionService.NO_PERIOD).send().join();
            return;
        }
        boolean locked = service.lock(period.get(), OpenProgressPeriodWorker.SERVICE_USER);
        LOG.info("lock-progress-period job {}: week ending {} {}", job.getKey(), period.get().weekEnding(), locked ? "locked" : "was locked already");
        client.newCompleteCommand(job.getKey()).send().join();
    }
}
