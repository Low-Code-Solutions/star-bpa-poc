package com.lcs.star.bpa.progress;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * BPA-65 (FRD-22 section 22.10; the window is an ASSUMPTION of the story): the daily close cost check runs from three days before the month lock day up to and including
 * the lock day. Pure date rule, no database. Lock day 1: 28 Nov to 1 Dec 2026 (the story Outline); lock day 16: the 13th to the 16th.
 */
class DailyCloseWindowTest {

    @Test
    void lockDay1CloseRunsFrom28NovTo1Dec() {
        assertThat(DailyCloseCostCheckService.inCloseWindow(LocalDate.of(2026, 11, 27), 1)).as("27 Nov is outside").isFalse();
        assertThat(DailyCloseCostCheckService.inCloseWindow(LocalDate.of(2026, 11, 28), 1)).as("28 Nov, three days before the lock day").isTrue();
        assertThat(DailyCloseCostCheckService.inCloseWindow(LocalDate.of(2026, 11, 30), 1)).isTrue();
        assertThat(DailyCloseCostCheckService.inCloseWindow(LocalDate.of(2026, 12, 1), 1)).as("1 Dec, the lock day itself").isTrue();
        assertThat(DailyCloseCostCheckService.inCloseWindow(LocalDate.of(2026, 12, 2), 1)).as("2 Dec, the close has ended").isFalse();
        assertThat(DailyCloseCostCheckService.inCloseWindow(LocalDate.of(2026, 12, 15), 1)).isFalse();
    }

    @Test
    void closeWindowForLockDay16() {
        assertThat(DailyCloseCostCheckService.inCloseWindow(LocalDate.of(2026, 11, 12), 16)).isFalse();
        assertThat(DailyCloseCostCheckService.inCloseWindow(LocalDate.of(2026, 11, 13), 16)).isTrue();
        assertThat(DailyCloseCostCheckService.inCloseWindow(LocalDate.of(2026, 11, 16), 16)).isTrue();
        assertThat(DailyCloseCostCheckService.inCloseWindow(LocalDate.of(2026, 11, 17), 16)).isFalse();
        assertThat(DailyCloseCostCheckService.inCloseWindow(LocalDate.of(2026, 11, 28), 16)).as("lock day 16 does not close at month end").isFalse();
    }

    @Test
    void theWindowCrossesAYearEnd() {
        assertThat(DailyCloseCostCheckService.inCloseWindow(LocalDate.of(2026, 12, 29), 1)).isTrue();
        assertThat(DailyCloseCostCheckService.inCloseWindow(LocalDate.of(2027, 1, 1), 1)).isTrue();
        assertThat(DailyCloseCostCheckService.inCloseWindow(LocalDate.of(2027, 1, 2), 1)).isFalse();
    }
}
