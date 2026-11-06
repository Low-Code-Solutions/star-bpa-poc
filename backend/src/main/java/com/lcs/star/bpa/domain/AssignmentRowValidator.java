package com.lcs.star.bpa.domain;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The row checks of {@code validate-worker-assignment} (BPA-22; D14, D32, D59, C11): the rows of one uploaded file that belong to one Work
 * Pack, each answered with one outcome text. Pure: no database, no process. A row gets the first rule that fails, in this order
 * (ASSUMPTION, plan Q1: the AC gives the texts, not the order):
 * <ol>
 * <li>Badge No blank: "Badge No is required"</li>
 * <li>the same Badge No on more than one row of the Work Pack: every such row "Worker listed twice for this Work Pack" (D32); counted
 * whatever else is wrong with the row</li>
 * <li>Badge No not in the Worker master data: "Worker not in worker master data" (exact match on the trimmed text, no case folding; Q2)</li>
 * <li>Estimated hours: required, a number, greater than 0, at most 2 decimal places (trailing zeros do not count), at most 99,999.99 (D14)</li>
 * <li>Remarks longer than 250 characters, counted as characters and not UTF-16 units, as PostgreSQL's char_length does (D59)</li>
 * </ol>
 * Otherwise "Assigned". The file's Employee name is never read (D59), and agency Workers are like any other (C11).
 */
public final class AssignmentRowValidator {

    public static final String ASSIGNED = "Assigned";

    static final BigDecimal MAX_HOURS = new BigDecimal("99999.99");
    static final int MAX_REMARKS = 250;

    /** One row of an uploaded file that belongs to one Work Pack, as read (raw text; null for an empty cell). */
    public record Row(int rowNumber, String badgeNo, String estimatedHours, String remarks) {
    }

    /** The outcome of one row; {@code hours} (two decimals) is set only when the row is accepted. */
    public record Verdict(String outcome, BigDecimal hours) {
        public boolean accepted() {
            return ASSIGNED.equals(outcome);
        }
    }

    private AssignmentRowValidator() {
    }

    /** One verdict per row, in the order of the rows. {@code knownBadgeNos} are the Badge Nos in the Worker master data. */
    public static List<Verdict> validate(List<Row> rows, Set<String> knownBadgeNos) {
        Map<String, Integer> timesListed = new HashMap<>();
        for (Row row : rows) {
            if (!isBlank(row.badgeNo())) {
                timesListed.merge(row.badgeNo().strip(), 1, Integer::sum);
            }
        }
        return rows.stream().map(row -> check(row, timesListed, knownBadgeNos)).toList();
    }

    private static Verdict check(Row row, Map<String, Integer> timesListed, Set<String> known) {
        if (isBlank(row.badgeNo())) {
            return rejected("Badge No is required");
        }
        String badge = row.badgeNo().strip();
        if (timesListed.get(badge) > 1) {
            return rejected("Worker listed twice for this Work Pack");
        }
        if (!known.contains(badge)) {
            return rejected("Worker not in worker master data");
        }
        if (isBlank(row.estimatedHours())) {
            return rejected("Estimated hours is required");
        }
        BigDecimal hours;
        try {
            hours = new BigDecimal(row.estimatedHours().strip());
        } catch (NumberFormatException e) {
            return rejected("Estimated hours must be a number");
        }
        if (hours.signum() <= 0) {
            return rejected("Estimated hours must be greater than 0");
        }
        if (hours.stripTrailingZeros().scale() > 2) {
            return rejected("Estimated hours can have at most 2 decimal places");
        }
        if (hours.compareTo(MAX_HOURS) > 0) {
            return rejected("Estimated hours cannot exceed 99,999.99");
        }
        if (row.remarks() != null && row.remarks().codePointCount(0, row.remarks().length()) > MAX_REMARKS) {
            return rejected("Remarks can be at most 250 characters");
        }
        return new Verdict(ASSIGNED, hours.setScale(2));
    }

    private static Verdict rejected(String outcome) {
        return new Verdict(outcome, null);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
