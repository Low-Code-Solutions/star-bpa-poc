package com.lcs.star.bpa.worker;

import com.lcs.star.bpa.persistence.ProgressPeriodRepository;
import com.lcs.star.bpa.persistence.ProgressPeriodRepository.Period;
import com.lcs.star.bpa.persistence.ProgressPeriodRepository.WeekAlreadyOpen;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Job type {@code open-progress-period} on {@code ServiceTask_OpenProgressPeriod} (BPA-58; E2-16 names it in the BPMN documentation, BPA-58's BPMN
 * Reference needs it: the FMS file belongs to a period). Creates progress_period for the week, status CutOff; from then on hours dated on or before the
 * week ending cannot be added or changed (BPA-50). Re-run key: the process instance key (ADR 0007, D65): a retried job finds its row and returns it.
 * The timer passes no date, so the week ending is the latest Thursday on or before today's business date (Asia/Riyadh, ADR 0013) and the cut-off time
 * is that Thursday 23:59 in the zone (ASSUMPTION, plan Q8). A second instance for a week that already has a period fails with no retries and an incident
 * (stale weekly instances, plan Q3); it creates nothing. Outputs: progressPeriodId, weekEnding (ISO date).
 */
@Component
public class OpenProgressPeriodWorker {

    static final String SERVICE_USER = "bpa-service";

    private static final Logger LOG = LoggerFactory.getLogger(OpenProgressPeriodWorker.class);

    private final ProgressPeriodRepository periods;
    private final Clock clock;
    private final ZoneId zone;

    public OpenProgressPeriodWorker(ProgressPeriodRepository periods, Clock clock, ZoneId businessZone) {
        this.periods = periods;
        this.clock = clock;
        this.zone = businessZone;
    }

    @JobWorker(type = "open-progress-period", autoComplete = false)
    public void handle(JobClient client, ActivatedJob job) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        LocalDate weekEnding = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.THURSDAY));
        Instant cutOffAt = weekEnding.atTime(23, 59).atZone(zone).toInstant();
        Period period;
        try {
            period = periods.open(job.getProcessInstanceKey(), weekEnding, cutOffAt, SERVICE_USER);
        } catch (WeekAlreadyOpen e) {
            LOG.warn("open-progress-period job {}: {}", job.getKey(), e.getMessage());
            client.newFailCommand(job.getKey()).retries(0).errorMessage(e.getMessage()).send().join();
            return;
        }
        LOG.info("open-progress-period job {}: progress period {} for week ending {}", job.getKey(), period.id(), period.weekEnding());
        client.newCompleteCommand(job.getKey())
                .variables(Map.of("progressPeriodId", period.id().toString(), "weekEnding", period.weekEnding().toString())).send().join();
    }
}
