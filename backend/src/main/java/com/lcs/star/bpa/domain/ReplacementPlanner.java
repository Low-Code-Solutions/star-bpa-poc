package com.lcs.star.bpa.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * STUB for the red run (L15): BPA-13's replacement rule is not built yet, so the plan is always empty. Replaced in the build step.
 */
public final class ReplacementPlanner {

    /** A Worker currently assigned to the Work Pack; {@code hoursLoaded} is true when a digital_timesheet_line with hours above 0 exists for the Worker and the Work Pack (D36). */
    public record Current(UUID workerId, String badgeNo, BigDecimal hours, boolean hoursLoaded) {
    }

    /** A Worker the replacement file asks for, with the hours already validated (two decimals). */
    public record Wanted(UUID workerId, String badgeNo, BigDecimal hours, String remarks) {
    }

    public enum Kind { ADDED, REPLACED, UNCHANGED, REMOVED, KEPT_FLAGGED }

    /** One decision per Worker: what happens to the assignment, with the hours before and after (0 for the absent side). */
    public record Step(Kind kind, UUID workerId, String badgeNo, BigDecimal oldHours, BigDecimal newHours) {
    }

    private ReplacementPlanner() {
    }

    public static List<Step> plan(List<Current> current, List<Wanted> wanted) {
        return List.of();
    }
}
