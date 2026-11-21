package com.lcs.star.bpa.progress;

import com.lcs.star.bpa.persistence.ActivityProgressRepository;
import com.lcs.star.bpa.persistence.ActivityProgressRepository.Shown;
import com.lcs.star.bpa.persistence.ProgressPeriodRepository;
import com.lcs.star.bpa.persistence.ProgressPeriodRepository.Period;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What {@code GET /api/v1/progress-periods/{weekEnding}/earned-manhours} shows (BPA-62; plan section 2): the week's stored rows with the week's increase,
 * which is derived and never stored: this week's Earned man-hours minus the latest Locked week's (0 when the activity has none; ASSUMPTION, plan Q7), absent
 * when this week has no Earned man-hours. A week with no progress period is empty ({@link Optional#empty()}), a week not calculated yet has no rows.
 */
@Service
public class EarnedManhoursReadService {

    /** One activity of the week as the endpoint shows it. */
    public record Row(String activityRef, String activityName, BigDecimal actualPct, BigDecimal budgetedUnits, BigDecimal earnedManhours, BigDecimal lastLockedEarnedManhours,
            BigDecimal weekIncrease) {
    }

    public record Week(LocalDate weekEnding, List<Row> rows) {
    }

    private final ProgressPeriodRepository periods;
    private final ActivityProgressRepository rows;

    public EarnedManhoursReadService(ProgressPeriodRepository periods, ActivityProgressRepository rows) {
        this.periods = periods;
        this.rows = rows;
    }

    @Transactional(readOnly = true)
    public Optional<Week> week(LocalDate weekEnding) {
        Optional<Period> period = periods.ofWeek(weekEnding);
        if (period.isEmpty()) {
            return Optional.empty();
        }
        List<Row> out = rows.shown(period.get().id(), weekEnding).stream().map(EarnedManhoursReadService::row).toList();
        return Optional.of(new Week(weekEnding, out));
    }

    private static Row row(Shown shown) {
        BigDecimal earned = shown.earnedManhours();
        BigDecimal increase = earned == null ? null : earned.subtract(shown.lastLockedEarnedManhours() == null ? BigDecimal.ZERO : shown.lastLockedEarnedManhours());
        return new Row(shown.activityRef(), shown.activityName(), shown.actualPct(), shown.budgetedUnits(), earned, shown.lastLockedEarnedManhours(), increase);
    }
}
