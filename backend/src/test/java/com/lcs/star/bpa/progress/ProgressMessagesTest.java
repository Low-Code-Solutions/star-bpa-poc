package com.lcs.star.bpa.progress;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** The texts of the progress rule (BPA-61); written with the implementation (plan: no red run for this unit test). */
class ProgressMessagesTest {

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    @Test
    void above100NamesTheWorkPackAndTheApprovedPercent() {
        assertThat(ProgressMessages.of("WP-000461", "ABOVE_100", d("120.00"), d("60"))).contains("WP-000461 completion cannot exceed 100% (approved 120%)");
        assertThat(ProgressMessages.of("WP-000461", "ABOVE_100", d("100.01"), d("60"))).contains("WP-000461 completion cannot exceed 100% (approved 100.01%)");
    }

    @Test
    void below0NamesTheWorkPack() {
        assertThat(ProgressMessages.of("WP-000457", "BELOW_0", d("-5"), d("30"))).contains("WP-000457 completion cannot be below 0%");
    }

    @Test
    void decreaseGivesThePreviousAndTheNewPercent() {
        assertThat(ProgressMessages.of("WP-000457", "DECREASE", d("25.50"), d("30.00"))).contains("WP-000457 completion fell from 30% to 25.5%; a retraction is needed");
    }

    @Test
    void acceptedHasNoMessage() {
        assertThat(ProgressMessages.of("WP-000457", null, d("30"), d("30"))).isEmpty();
    }

    @Test
    void numbersHaveNoTrailingZerosOrExponent() {
        assertThat(ProgressMessages.number(d("100"))).isEqualTo("100");
        assertThat(ProgressMessages.number(d("0"))).isEqualTo("0");
        assertThat(ProgressMessages.number(d("1E+2"))).isEqualTo("100");
    }
}
