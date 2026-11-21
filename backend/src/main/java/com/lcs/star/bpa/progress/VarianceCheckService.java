package com.lcs.star.bpa.progress;

import com.lcs.star.bpa.persistence.CbsActualsRepository;
import com.lcs.star.bpa.persistence.CbsActualsRepository.Forecast;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The variance checks of a week (BPA-64; E2-14; plan section 1): one check per CBS that has Approved hours up to the week ending or a Current Forecast row (plan Q3).
 * {@code actualManhours} is the cumulative Approved line hours whose time sheet carries the CBS; lines with no CBS are the "No CBS" total and never a check (D40).
 * {@code revisedBudgetCost} and {@code actualCost} are passed for BPA-65's cost decision (either may be null: the cost decision then gives NO_BUDGET). Pure reads, so
 * the same week gives the same checks; the read API and the Prepare step share this.
 */
@Service
public class VarianceCheckService {

    /** One CBS's check: the inputs of Decision_ManhourVariance and Decision_CostVariance. */
    public record Check(String cbsCodeRef, BigDecimal actualManhours, BigDecimal currentForecastManhours, BigDecimal revisedBudgetCost, BigDecimal actualCost) {

        /** As the process variable {@code varianceChecks} holds it; absent figures are null. */
        public Map<String, Object> asVariable() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("cbsCodeRef", cbsCodeRef);
            out.put("actualManhours", actualManhours);
            out.put("currentForecastManhours", currentForecastManhours);
            out.put("revisedBudgetCost", revisedBudgetCost);
            out.put("actualCost", actualCost);
            return out;
        }
    }

    private final CbsActualsRepository actuals;

    public VarianceCheckService(CbsActualsRepository actuals) {
        this.actuals = actuals;
    }

    @Transactional(readOnly = true)
    public List<Check> checks(LocalDate weekEnding) {
        Map<String, BigDecimal> hours = actuals.approvedByCbs(weekEnding);
        Map<String, Forecast> forecasts = actuals.forecasts();
        TreeSet<String> codes = new TreeSet<>(hours.keySet());
        codes.addAll(forecasts.keySet());
        List<Check> checks = new ArrayList<>();
        for (String code : codes) {
            Forecast forecast = forecasts.get(code);
            checks.add(new Check(code, hours.getOrDefault(code, BigDecimal.ZERO).setScale(2), forecast == null ? null : forecast.currentForecastManhours(),
                    forecast == null ? null : forecast.revisedBudgetCost(), actuals.actualCost(code, weekEnding)));
        }
        return checks;
    }

    @Transactional(readOnly = true)
    public BigDecimal noCbsManhours(LocalDate weekEnding) {
        return actuals.approvedWithoutCbs(weekEnding).setScale(2);
    }
}
