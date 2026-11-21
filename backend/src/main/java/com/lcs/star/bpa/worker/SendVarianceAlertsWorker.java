package com.lcs.star.bpa.worker;

import com.lcs.star.bpa.persistence.ProgressPeriodRepository.Period;
import com.lcs.star.bpa.progress.CostVarianceMath;
import com.lcs.star.bpa.progress.ProgressCheckService;
import com.lcs.star.bpa.progress.VarianceAlertService;
import com.lcs.star.bpa.progress.VarianceAlertService.CostOver;
import com.lcs.star.bpa.progress.VarianceAlertService.Over;
import com.lcs.star.bpa.progress.VarianceMath;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Job type {@code send-variance-alerts} on {@code ServiceTask_SendVarianceAlerts} (BPA-64, BPA-65; E2-14, E2-15; D3; ADR 0007 and 0012).
 * <ul>
 * <li>{@code manhourCheckResults} (one entry per CBS: {@code cbsCodeRef}, {@code actualManhours}, {@code currentForecastManhours}, {@code manhourResult}): each OVER_15_PCT
 * entry sends the man-hour alert to the Cost Controller (email and in the system) and by email to the PCON Manager's address, once per period, CBS, recipient and channel;</li>
 * <li>{@code costCheckResults} (one entry per CBS: {@code cbsCodeRef}, {@code revisedBudgetCost}, {@code actualCost}, {@code costResult}): each OVER_10_PCT entry sends the cost
 * alert to the Project Manager (email and in the system) and by email to the PCON Manager's address, once per CBS, recipient, channel and day, shared with the daily close check.
 * The text carries the date of the check (today in Asia/Riyadh), not the week ending.</li>
 * </ul>
 * A week with no progress period, an entry that cannot be read, or an over-limit CBS with nobody to alert fails the job with no retries; a mail failure is not caught
 * (Camunda's tries, then an incident), as BPA-16. No output variable.
 */
@Component
public class SendVarianceAlertsWorker {

    private static final Logger LOG = LoggerFactory.getLogger(SendVarianceAlertsWorker.class);

    private final ProgressCheckService periods;
    private final VarianceAlertService service;
    private final Clock clock;
    private final ZoneId zone;

    public SendVarianceAlertsWorker(ProgressCheckService periods, VarianceAlertService service, Clock clock, ZoneId businessZone) {
        this.periods = periods;
        this.service = service;
        this.clock = clock;
        this.zone = businessZone;
    }

    @JobWorker(type = "send-variance-alerts", autoComplete = false)
    public void handle(JobClient client, ActivatedJob job) {
        Map<String, Object> variables = job.getVariablesAsMap();
        Optional<Period> period = periods.periodOf(job.getProcessInstanceKey(), variables.get("progressPeriodId"));
        if (period.isEmpty()) {
            LOG.warn("send-variance-alerts job {}: the week of this instance has no progress period", job.getKey());
            client.newFailCommand(job.getKey()).retries(0).errorMessage("The week of this instance has no progress period").send().join();
            return;
        }
        List<Over> overs;
        List<CostOver> costOvers;
        try {
            overs = overs(variables.get("manhourCheckResults"));
            costOvers = costOvers(variables.get("costCheckResults"));
        } catch (IllegalArgumentException e) {
            LOG.warn("send-variance-alerts job {}: {}", job.getKey(), e.getMessage());
            client.newFailCommand(job.getKey()).retries(0).errorMessage(e.getMessage()).send().join();
            return;
        }
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        try {
            service.sendManhourAlerts(period.get(), overs, OpenProgressPeriodWorker.SERVICE_USER);
            service.sendCostAlerts(period.get(), today, costOvers, OpenProgressPeriodWorker.SERVICE_USER);
        } catch (IllegalStateException e) {
            LOG.warn("send-variance-alerts job {}: {}", job.getKey(), e.getMessage());
            client.newFailCommand(job.getKey()).retries(0).errorMessage(e.getMessage()).send().join();
            return;
        }
        LOG.info("send-variance-alerts job {}: {} CBS over the forecast and {} over the budget for week ending {}", job.getKey(), overs.size(), costOvers.size(),
                period.get().weekEnding());
        client.newCompleteCommand(job.getKey()).send().join();
    }

    private static List<Over> overs(Object results) {
        List<Over> overs = new ArrayList<>();
        if (results instanceof List<?> entries) {
            for (Object entry : entries) {
                if (!(entry instanceof Map<?, ?> map) || !(map.get("cbsCodeRef") instanceof String cbs)) {
                    throw new IllegalArgumentException("A man-hour check result has no CBS");
                }
                if (VarianceMath.OVER_15_PCT.equals(map.get("manhourResult"))) {
                    BigDecimal actual = number(map.get("actualManhours"));
                    BigDecimal forecast = number(map.get("currentForecastManhours"));
                    if (actual == null || forecast == null) {
                        throw new IllegalArgumentException("The man-hour check result of " + cbs + " has no actual or forecast");
                    }
                    overs.add(new Over(cbs, actual, forecast));
                }
            }
        }
        return overs;
    }

    private static List<CostOver> costOvers(Object results) {
        List<CostOver> overs = new ArrayList<>();
        if (results instanceof List<?> entries) {
            for (Object entry : entries) {
                if (!(entry instanceof Map<?, ?> map) || !(map.get("cbsCodeRef") instanceof String cbs)) {
                    throw new IllegalArgumentException("A cost check result has no CBS");
                }
                if (CostVarianceMath.OVER_10_PCT.equals(map.get("costResult"))) {
                    BigDecimal actual = number(map.get("actualCost"));
                    BigDecimal budget = number(map.get("revisedBudgetCost"));
                    if (actual == null || budget == null) {
                        throw new IllegalArgumentException("The cost check result of " + cbs + " has no actual cost or revised budget");
                    }
                    overs.add(new CostOver(cbs, actual, budget));
                }
            }
        }
        return overs;
    }

    private static BigDecimal number(Object value) {
        return value instanceof Number ? new BigDecimal(value.toString()) : null;
    }
}
