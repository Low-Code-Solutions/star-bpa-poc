package com.lcs.star.bpa.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** BPA-50: the pure line checks (messages are the story's; the week ends on Thursday, E2-D15). */
class HourLineChecksTest {

    @Test
    void weekEndsOnTheThursdayOnOrAfterTheDate() {
        assertThat(HourLineChecks.weekEnding(LocalDate.of(2026, 11, 12))).as("a Thursday is its own week end").isEqualTo(LocalDate.of(2026, 11, 12));
        assertThat(HourLineChecks.weekEnding(LocalDate.of(2026, 11, 13))).as("the Friday starts the next week").isEqualTo(LocalDate.of(2026, 11, 19));
        assertThat(HourLineChecks.weekEnding(LocalDate.of(2026, 11, 18))).isEqualTo(LocalDate.of(2026, 11, 19));
    }

    @Test
    void openWeekStartsOnTheFridayOrAfterTheLatestCutOff() {
        LocalDate wednesday = LocalDate.of(2026, 11, 18);
        assertThat(HourLineChecks.openWeekStart(wednesday, Optional.empty())).isEqualTo(LocalDate.of(2026, 11, 13));
        assertThat(HourLineChecks.openWeekStart(wednesday, Optional.of(LocalDate.of(2026, 11, 12)))).isEqualTo(LocalDate.of(2026, 11, 13));
        assertThat(HourLineChecks.openWeekStart(LocalDate.of(2026, 11, 19), Optional.of(LocalDate.of(2026, 11, 19)))).as("after this week's cut-off")
                .isEqualTo(LocalDate.of(2026, 11, 20));
    }

    @Test
    void hoursAreAboveZeroWithTwoDecimalsAtMost() {
        assertThat(HourLineChecks.hours(new BigDecimal("0"))).contains("Hours must be greater than 0");
        assertThat(HourLineChecks.hours(new BigDecimal("-1"))).contains("Hours must be greater than 0");
        assertThat(HourLineChecks.hours(new BigDecimal("7.125"))).contains("Hours can have at most 2 decimal places");
        assertThat(HourLineChecks.hours(new BigDecimal("0.01"))).isEmpty();
        assertThat(HourLineChecks.hours(new BigDecimal("7.500"))).as("trailing zeros are not decimals").isEmpty();
    }

    @Test
    void remarksAreAtMost250Characters() {
        assertThat(HourLineChecks.remarks("x".repeat(250))).isEmpty();
        assertThat(HourLineChecks.remarks("x".repeat(251))).contains("Remarks can be at most 250 characters");
        assertThat(HourLineChecks.remarks(null)).isEmpty();
    }

    @Test
    void dateAndCompletionMessages() {
        assertThat(HourLineChecks.show(LocalDate.of(2026, 11, 17))).isEqualTo("17 Nov 2026");
        assertThat(HourLineChecks.weekClosed(LocalDate.of(2026, 11, 12))).isEqualTo("Week ending 12 Nov 2026 is closed");
        assertThat(HourLineChecks.notInTheFuture(LocalDate.of(2026, 11, 19), LocalDate.of(2026, 11, 18))).contains("Work date cannot be in the future");
        assertThat(HourLineChecks.notInTheFuture(LocalDate.of(2026, 11, 18), LocalDate.of(2026, 11, 18))).isEmpty();
        assertThat(HourLineChecks.completion(new BigDecimal("100"))).isEmpty();
        assertThat(HourLineChecks.completion(new BigDecimal("100.01"))).isPresent();
        assertThat(HourLineChecks.completion(null)).isEmpty();
    }
}
