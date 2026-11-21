package com.lcs.star.bpa.progress;

import static org.assertj.core.api.Assertions.assertThat;

import com.lcs.star.bpa.persistence.ActivityProgressRepository.Counted;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The roll-up and the rounding of BPA-62, pure; written with the implementation (plan: no red run for this unit test). */
class EarnedManhoursServiceTest {

    private static Counted wp(String budget, String pct, String weight) {
        return new Counted("A-2201", budget == null ? null : new BigDecimal(budget), new BigDecimal(pct), new BigDecimal(weight));
    }

    private static EarnedManhoursService.Result only(List<Counted> counted) {
        List<EarnedManhoursService.Result> results = new ArrayList<>();
        EarnedManhoursService.roll(counted).forEach(results::add);
        assertThat(results).hasSize(1);
        return results.get(0);
    }

    @Test
    void theRollUpIsWeightedByEstimatedHours() {
        EarnedManhoursService.Result r = only(List.of(wp("1200", "40", "500"), wp("1200", "100", "100")));

        assertThat(r.actualPct()).isEqualByComparingTo("50.00");
        assertThat(r.earnedManhours()).isEqualByComparingTo("600.00");
    }

    @Test
    void earnedComesFromTheUnroundedPercent() {
        EarnedManhoursService.Result r = only(List.of(wp("1200", "40", "500"), wp("1200", "0", "100")));

        assertThat(r.actualPct()).isEqualByComparingTo("33.33");
        assertThat(r.earnedManhours()).as("33.333... x 1200 / 100, not 33.33 x 1200 / 100 = 399.96").isEqualByComparingTo("400.00");
    }

    @Test
    void earnedIsRoundedHalfUpToTwoDecimals() {
        assertThat(only(List.of(wp("1000.01", "33.33", "500"))).earnedManhours()).isEqualByComparingTo("333.30");
        assertThat(only(List.of(wp("100.05", "50", "10"))).earnedManhours()).as("50.025 goes up").isEqualByComparingTo("50.03");
    }

    @Test
    void noBudgetedUnitsGivesNoEarned() {
        EarnedManhoursService.Result r = only(List.of(wp(null, "10", "100")));

        assertThat(r.actualPct()).isEqualByComparingTo("10.00");
        assertThat(r.budgetedUnits()).isNull();
        assertThat(r.earnedManhours()).isNull();
    }

    @Test
    void noPhaseHoursGivesZeroPercentNotADivisionByZero() {
        EarnedManhoursService.Result r = only(List.of(wp("1200", "40", "0")));

        assertThat(r.actualPct()).isEqualByComparingTo("0.00");
        assertThat(r.earnedManhours()).isEqualByComparingTo("0.00");
    }
}
