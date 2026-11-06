package com.lcs.star.bpa.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * STUB for the red run (L15): BPA-22's row checks are not built yet, so every row stays "Pending". Replaced in the build step.
 */
public final class AssignmentRowValidator {

    public static final String ASSIGNED = "Assigned";

    /** One row of an uploaded file that belongs to one Work Pack, as read (raw text). */
    public record Row(int rowNumber, String badgeNo, String estimatedHours, String remarks) {
    }

    /** The outcome of one row; {@code hours} is set only when the row is accepted. */
    public record Verdict(String outcome, BigDecimal hours) {
        public boolean accepted() {
            return ASSIGNED.equals(outcome);
        }
    }

    private AssignmentRowValidator() {
    }

    public static List<Verdict> validate(List<Row> rows, Set<String> knownBadgeNos) {
        return rows.stream().map(r -> new Verdict("Pending", null)).toList();
    }
}
