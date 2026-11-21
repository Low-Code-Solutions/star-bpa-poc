package com.lcs.star.bpa.progress;

import com.lcs.star.bpa.persistence.CbsReviewRepository;
import com.lcs.star.bpa.persistence.ProgressPeriodRepository;
import com.lcs.star.bpa.persistence.ProgressPeriodRepository.Period;
import com.lcs.star.bpa.persistence.VarianceAlertRepository;
import com.lcs.star.bpa.progress.VarianceCheckService.Check;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * What {@code GET /api/v1/progress-periods/{weekEnding}/cbs-actuals} shows (BPA-64; plan section 5): per CBS the actual man-hours (live, with the same query as the
 * check), the Current Forecast, the ETC, the variance %, the result of Decision_ManhourVariance (worked out in {@link VarianceMath}, plan Q10), whether a Manhour alert
 * was raised for the CBS in this period (the stored alert rows) and the Cost Controller's remark; plus the "No CBS" total (D40). Derived, nothing stored. A week with
 * no progress period is empty ({@link Optional#empty()}).
 */
@Service
public class CbsActualsReadService {

    /** One CBS of the week. */
    public record Row(String cbsCodeRef, BigDecimal actualManhours, BigDecimal currentForecastManhours, BigDecimal etc, BigDecimal variancePct, String result, boolean alert,
            String remark) {
    }

    public record Week(LocalDate weekEnding, List<Row> rows, BigDecimal noCbsManhours) {
    }

    private final ProgressPeriodRepository periods;
    private final VarianceCheckService checks;
    private final VarianceAlertRepository alerts;
    private final CbsReviewRepository reviews;

    public CbsActualsReadService(ProgressPeriodRepository periods, VarianceCheckService checks, VarianceAlertRepository alerts, CbsReviewRepository reviews) {
        this.periods = periods;
        this.checks = checks;
        this.alerts = alerts;
        this.reviews = reviews;
    }

    public Optional<Week> week(LocalDate weekEnding) {
        return periods.ofWeek(weekEnding).map(this::week);
    }

    public Week week(Period period) {
        Set<String> alerted = alerts.alertedCbs(period.id(), VarianceAlertRepository.MANHOUR);
        Map<String, String> remarks = reviews.remarks(period.id());
        List<Row> rows = checks.checks(period.weekEnding()).stream().map(check -> row(check, alerted.contains(check.cbsCodeRef()), remarks.get(check.cbsCodeRef()))).toList();
        return new Week(period.weekEnding(), rows, checks.noCbsManhours(period.weekEnding()));
    }

    private static Row row(Check check, boolean alert, String remark) {
        BigDecimal actual = check.actualManhours();
        BigDecimal forecast = check.currentForecastManhours();
        boolean has = VarianceMath.hasForecast(forecast);
        return new Row(check.cbsCodeRef(), actual, forecast, has ? VarianceMath.etc(actual, forecast) : null, VarianceMath.variancePct(actual, forecast),
                VarianceMath.result(actual, forecast), alert, remark);
    }
}
