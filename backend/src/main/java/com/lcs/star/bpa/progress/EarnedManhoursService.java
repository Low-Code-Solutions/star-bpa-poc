package com.lcs.star.bpa.progress;

import com.lcs.star.bpa.persistence.ActivityProgressRepository;
import com.lcs.star.bpa.persistence.ActivityProgressRepository.Counted;
import com.lcs.star.bpa.persistence.ActivityProgressRepository.Stored;
import com.lcs.star.bpa.persistence.ProgressPeriodRepository;
import com.lcs.star.bpa.persistence.ProgressPeriodRepository.Period;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Activity progress and Earned man-hours for a week (BPA-62; E2-13; plan section 1). For every activity with a Released, In Progress or Completed Work Pack:
 * the activity % = Σ(Work Pack % × weight) ÷ Σ weight, weight = the Work Pack's estimated man-hours (Σ phase man-hours), stored half-up to 2 decimals; Earned
 * man-hours = the activity % ÷ 100 × the activity's budgeted units, half-up to 2 decimals, worked out from the unrounded % (plan Q1) and NULL when the activity
 * has no budgeted units. Upsert for a week that is not Locked, in one transaction (a week the Project Manager sends back is recalculated, BPA-66); a row of an
 * activity that no longer has a counted Work Pack is deleted (plan Q2, ASSUMPTION); a Locked week is refused and never written. The same inputs give the same
 * rows and write nothing.
 */
@Service
public class EarnedManhoursService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final ProgressPeriodRepository periods;
    private final ActivityProgressRepository rows;

    public EarnedManhoursService(ProgressPeriodRepository periods, ActivityProgressRepository rows) {
        this.periods = periods;
        this.rows = rows;
    }

    /** One activity's result before it is stored. */
    record Result(String activityRef, BigDecimal actualPct, BigDecimal budgetedUnits, BigDecimal earnedManhours) {
    }

    /** Calculates and stores the week; a Locked week is an {@link IllegalArgumentException} and nothing is written. */
    @Transactional
    public void calculate(Period period, String username) {
        periods.requireOpen(period); // BPA-67: Locked week refused, the period row held for this transaction (plan Q3)
        Map<String, Stored> stored = new HashMap<>();
        rows.storedForUpdate(period.id()).forEach(row -> stored.put(row.activityRef(), row));
        for (Result result : roll(rows.counted(period.id(), period.weekEnding()))) {
            Stored old = stored.remove(result.activityRef());
            if (old == null) {
                rows.insert(period.id(), result.activityRef(), result.actualPct(), result.budgetedUnits(), result.earnedManhours(), username);
            } else {
                rows.update(old, result.actualPct(), result.budgetedUnits(), result.earnedManhours(), username);
            }
        }
        stored.values().forEach(old -> rows.delete(old, username));
    }

    /** The roll-up of the counted Work Packs, by activity (pure). */
    static Iterable<Result> roll(java.util.List<Counted> counted) {
        Map<String, BigDecimal[]> sums = new LinkedHashMap<>(); // [Σ pct × weight, Σ weight]
        Map<String, BigDecimal> units = new HashMap<>();
        for (Counted wp : counted) {
            BigDecimal[] sum = sums.computeIfAbsent(wp.activityRef(), k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            sum[0] = sum[0].add(wp.pct().multiply(wp.weight()));
            sum[1] = sum[1].add(wp.weight());
            units.put(wp.activityRef(), wp.budgetedUnits());
        }
        java.util.List<Result> results = new java.util.ArrayList<>();
        sums.forEach((activity, sum) -> {
            BigDecimal budget = units.get(activity);
            boolean noWeight = sum[1].signum() == 0; // no phase hours: 0% instead of a division by zero (plan Q2, ASSUMPTION)
            BigDecimal actual = noWeight ? BigDecimal.ZERO.setScale(2) : sum[0].divide(sum[1], 2, RoundingMode.HALF_UP);
            BigDecimal earned = budget == null ? null
                    : noWeight ? BigDecimal.ZERO.setScale(2) : sum[0].multiply(budget).divide(sum[1].multiply(HUNDRED), 2, RoundingMode.HALF_UP); // one division: the unrounded %
            results.add(new Result(activity, actual, budget, earned));
        });
        return results;
    }
}
