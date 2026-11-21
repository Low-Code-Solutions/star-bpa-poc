package com.lcs.star.bpa.progress;

import com.fasterxml.jackson.databind.JsonNode;
import com.lcs.star.bpa.adapter.CamundaDecisionClient;
import com.lcs.star.bpa.config.AlertSettings;
import com.lcs.star.bpa.progress.VarianceAlertService.CostOver;
import com.lcs.star.bpa.progress.VarianceCheckService.Check;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The daily close cost check (BPA-65; E2-15; FRD-22 §22.10; plan Q1 option A). FRD-22 asks for the cost check daily during the month-end close; the close window is an
 * ASSUMPTION of the story: from three days before the month lock day ({@link AlertSettings#monthLockDay}, the setting {@code MonthLockJob} reads) up to and including the lock day.
 * Inside the window every CBS is checked with the deployed {@code Decision_CostVariance} (evaluated through {@link CamundaDecisionClient}, so the rules stay in the model) and
 * each OVER_10_PCT result is alerted through the same sender as the weekly check ({@link VarianceAlertService#sendCostAlerts}, no period), so a CBS alerts a recipient on a channel
 * once a day however many checks run. The cost is summed up to the month of the date (data model "(A)"); outside the window nothing is evaluated or written.
 */
@Service
public class DailyCloseCostCheckService {

    private static final Logger LOG = LoggerFactory.getLogger(DailyCloseCostCheckService.class);
    static final String DECISION = "Decision_CostVariance";
    static final String SERVICE_USER = "bpa-service";
    /** Days before the lock day on which the close starts (ASSUMPTION of the story). */
    static final int CLOSE_DAYS_BEFORE = 3;

    private final VarianceCheckService checks;
    private final CamundaDecisionClient decisions;
    private final VarianceAlertService alerts;
    private final AlertSettings settings;

    public DailyCloseCostCheckService(VarianceCheckService checks, CamundaDecisionClient decisions, VarianceAlertService alerts, AlertSettings settings) {
        this.checks = checks;
        this.decisions = decisions;
        this.alerts = alerts;
        this.settings = settings;
    }

    /** True when the date is in the close: the lock day of this month or of next month is 0 to 3 days away. */
    public static boolean inCloseWindow(LocalDate date, int monthLockDay) {
        for (LocalDate lockDay : List.of(date.withDayOfMonth(monthLockDay), date.plusMonths(1).withDayOfMonth(monthLockDay))) {
            long days = ChronoUnit.DAYS.between(date, lockDay);
            if (days >= 0 && days <= CLOSE_DAYS_BEFORE) {
                return true;
            }
        }
        return false;
    }

    /** Runs the check for the date (the business date at 06:00 Asia/Riyadh); does nothing outside the close. */
    public void runFor(LocalDate today) {
        if (!inCloseWindow(today, settings.monthLockDay())) {
            LOG.debug("{} is outside the close window: no daily cost check", today);
            return;
        }
        List<CostOver> overs = new ArrayList<>();
        for (Check check : checks.checks(today)) {
            Map<String, Object> variables = new HashMap<>();
            variables.put("revisedBudgetCost", check.revisedBudgetCost());
            variables.put("actualCost", check.actualCost());
            JsonNode output = decisions.evaluate(DECISION, variables);
            if (CostVarianceMath.OVER_10_PCT.equals(output.path("costResult").asText())) {
                overs.add(new CostOver(check.cbsCodeRef(), check.actualCost(), check.revisedBudgetCost()));
            }
        }
        LOG.info("Daily close cost check {}: {} CBS over the Revised Budget", today, overs.size());
        alerts.sendCostAlerts(null, today, overs, SERVICE_USER);
    }
}
