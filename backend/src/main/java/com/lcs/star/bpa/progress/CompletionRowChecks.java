package com.lcs.star.bpa.progress;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The row rules of the completion report upload (BPA-59; E2-09), pure so a unit test covers them. A row gets one outcome, "Read" or the first rule it
 * breaks, in this order (the order is an ASSUMPTION; the texts are Jira's unless marked):
 * <ol>
 * <li>a required value is missing: "Workpack number is required", "Completion % is required", "Source is required" (ASSUMPTION texts);</li>
 * <li>"Work Pack not found"; "Work Pack is not In Progress" for any other status (ASSUMPTION, in the story);</li>
 * <li>"Duplicate row for &lt;WP&gt;": an earlier row of the file names the same Work Pack, whatever that row's outcome (ASSUMPTION, plan Q11);</li>
 * <li>"Completion % must be a number"; "Completion % can have at most 2 decimal places"; a value that does not fit numeric(5,2) is "Completion % must be
 * between -999.99 and 999.99" (ASSUMPTION text). 120 and negative values are read: BPA-61 blocks them at approval (decision (e));</li>
 * <li>Actual hours, when not empty, "Actual hours must be a number of 0 or more" (ASSUMPTION text);</li>
 * <li>"Source &lt;x&gt; is not the configured progress source", with &lt;x&gt; as written (C-E2-3).</li>
 * </ol>
 */
public final class CompletionRowChecks {

    public static final String READ = "Read";
    public static final String WORKPACK_REQUIRED = "Workpack number is required";
    public static final String COMPLETION_REQUIRED = "Completion % is required";
    public static final String SOURCE_REQUIRED = "Source is required";
    public static final String WORKPACK_NOT_FOUND = "Work Pack not found";
    public static final String NOT_IN_PROGRESS = "Work Pack is not In Progress";
    public static final String COMPLETION_NOT_A_NUMBER = "Completion % must be a number";
    public static final String COMPLETION_DECIMALS = "Completion % can have at most 2 decimal places";
    public static final String COMPLETION_RANGE = "Completion % must be between -999.99 and 999.99";
    public static final String HOURS_INVALID = "Actual hours must be a number of 0 or more";

    /** A plain decimal: optional sign, digits with an optional fraction (no exponent, no thousands separator, no comma). */
    private static final Pattern NUMBER = Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)");
    private static final BigDecimal LIMIT = new BigDecimal("1000");

    private CompletionRowChecks() {
    }

    /** The cells of a row as written; a missing cell is null. */
    public record Raw(String workpackNumber, String completion, String actualHours, String source) {
    }

    /** The outcome of one row. {@code seen} holds the Work Pack numbers of the earlier rows and is updated here. */
    public static String check(Raw row, Set<String> seen, Function<String, Optional<String>> workPackStatus, String configuredSource) {
        if (row.workpackNumber() == null) {
            return WORKPACK_REQUIRED;
        }
        boolean duplicate = !seen.add(row.workpackNumber()); // counted from its number, whatever else the row lacks (QA review)
        if (row.completion() == null) {
            return COMPLETION_REQUIRED;
        }
        if (row.source() == null) {
            return SOURCE_REQUIRED;
        }
        Optional<String> status = workPackStatus.apply(row.workpackNumber());
        if (status.isEmpty()) {
            return WORKPACK_NOT_FOUND;
        }
        if (!"In Progress".equals(status.get())) {
            return NOT_IN_PROGRESS;
        }
        if (duplicate) {
            return "Duplicate row for " + row.workpackNumber();
        }
        Optional<BigDecimal> completion = number(row.completion());
        if (completion.isEmpty()) {
            return COMPLETION_NOT_A_NUMBER;
        }
        if (completion.get().stripTrailingZeros().scale() > 2) {
            return COMPLETION_DECIMALS;
        }
        if (completion.get().abs().compareTo(LIMIT) >= 0) {
            return COMPLETION_RANGE;
        }
        if (row.actualHours() != null && number(row.actualHours()).filter(h -> h.signum() >= 0).isEmpty()) {
            return HOURS_INVALID;
        }
        if (!configuredSource.equalsIgnoreCase(row.source().trim())) {
            return "Source " + row.source().trim() + " is not the configured progress source";
        }
        return READ;
    }

    /** The value as a number, or empty when the text is not a plain decimal. */
    public static Optional<BigDecimal> number(String text) {
        String trimmed = text.trim();
        return NUMBER.matcher(trimmed).matches() ? Optional.of(new BigDecimal(trimmed.startsWith("+") ? trimmed.substring(1) : trimmed)) : Optional.empty();
    }

    /** The Source the POC understands, FMS or PMS (upper case), or empty for anything else (logged raw only, plan Q1). */
    public static Optional<String> knownSource(String written) {
        if (written == null) {
            return Optional.empty();
        }
        String upper = written.trim().toUpperCase(Locale.ROOT);
        return "FMS".equals(upper) || "PMS".equals(upper) ? Optional.of(upper) : Optional.empty();
    }
}
