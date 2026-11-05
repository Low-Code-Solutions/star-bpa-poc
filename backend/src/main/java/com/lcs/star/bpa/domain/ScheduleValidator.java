package com.lcs.star.bpa.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * BPA-11 (test-first stub, replaced when the validator is built): accepts every schedule, which is the expected red state for the
 * BPA11_ tests that need the save step's checks (D9, D56).
 */
public final class ScheduleValidator {

    private ScheduleValidator() {
    }

    public static List<String> validate(LocalDate plannedStart, LocalDate plannedEnd, LocalDate today, int leadTimeDays) {
        return List.of();
    }
}
