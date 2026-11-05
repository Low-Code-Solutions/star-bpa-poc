package com.lcs.star.bpa.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * BPA-11: the save step's schedule rules (D9, D56, F10), pure. Not Gherkin scenarios themselves: BPA11_plannedDateBeforeLeadTimeIsRejected
 * and BPA11_plannedEndBeforeStartIsRejected (ScheduleApiTest) prove the same rules through the real process. Today is 21 Oct 2026
 * (Asia/Riyadh) with a lead time of 7 days, so the earliest allowed date is 28 Oct 2026.
 * ASSUMPTION texts: "Planned start is required", "Planned end is required" (the story states none).
 */
class ScheduleValidatorTest {

    static final LocalDate TODAY = LocalDate.parse("2026-10-21");
    static final String TOO_EARLY = "Date cannot be earlier than 28 Oct 2026";
    static final String END_BEFORE_START = "Planned end cannot be before planned start";

    private static List<String> check(String start, String end) {
        return ScheduleValidator.validate(start == null ? null : LocalDate.parse(start), end == null ? null : LocalDate.parse(end), TODAY, 7);
    }

    @Test
    void leadTimeBoundaryOnTheStartDate() {
        // Examples: | planned start | 27 Oct 2026 | rejected |, | planned start | 28 Oct 2026 | accepted |
        assertThat(check("2026-10-27", "2026-11-06")).containsExactly(TOO_EARLY);
        assertThat(check("2026-10-28", "2026-11-06")).isEmpty();
    }

    @Test
    void leadTimeAppliesToTheEndDateToo() {
        // Examples: | planned end | 27 Oct 2026 | rejected |; checked before the order, so no "end before start" message (PO note, 28 Oct)
        assertThat(check("2026-11-03", "2026-10-27")).containsExactly(TOO_EARLY);
    }

    @Test
    void bothDatesTooEarlyGiveOneMessage() {
        assertThat(check("2026-10-26", "2026-10-27")).containsExactly(TOO_EARLY);
    }

    @Test
    void endBeforeStartIsRejectedOnlyWhenBothDatesPassTheLeadTime() {
        assertThat(check("2026-11-05", "2026-11-04")).containsExactly(END_BEFORE_START);
        assertThat(check("2026-11-05", "2026-11-05")).as("the same day is fine").isEmpty();
        assertThat(check("2026-10-27", "2026-10-26")).as("lead time first, then the order").containsExactly(TOO_EARLY);
    }

    @Test
    void missingDatesAreRequired() {
        assertThat(check(null, "2026-11-06")).containsExactly("Planned start is required");
        assertThat(check("2026-11-03", null)).containsExactly("Planned end is required");
        assertThat(check(null, null)).containsExactly("Planned start is required", "Planned end is required");
    }

    @Test
    void theLeadTimeComesFromConfigurationAndDatesAreZeroPadded() {
        // 25 Oct + 7 days is 01 Nov; the text pads the day like the screen's dates ("06 Nov 2026").
        assertThat(ScheduleValidator.validate(LocalDate.parse("2026-10-31"), LocalDate.parse("2026-11-06"), LocalDate.parse("2026-10-25"), 7))
                .containsExactly("Date cannot be earlier than 01 Nov 2026");
        assertThat(ScheduleValidator.validate(LocalDate.parse("2026-10-24"), LocalDate.parse("2026-11-06"), TODAY, 3)).isEmpty();
        assertThat(ScheduleValidator.validate(LocalDate.parse("2026-10-23"), LocalDate.parse("2026-11-06"), TODAY, 3))
                .containsExactly("Date cannot be earlier than 24 Oct 2026");
    }

    @Test
    void aLateEndIsNotThisChecksBusiness() {
        // D26: an end after the date to complete is a notice on the screen; the save succeeds.
        assertThat(check("2026-11-03", "2026-11-12")).isEmpty();
    }
}
