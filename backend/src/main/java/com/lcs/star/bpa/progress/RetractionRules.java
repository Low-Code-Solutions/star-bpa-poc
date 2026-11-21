package com.lcs.star.bpa.progress;

import com.lcs.star.bpa.persistence.ProgressRetractionRepository.Request;
import java.math.BigDecimal;
import java.util.List;

/**
 * When PPC must give a retraction reason (BPA-63; FRD-21 §21.9.1, §21.7): the approved % is below last week's locked % and no Approved retraction of the Work
 * Pack is for exactly this % (BPA-61 decision (a): an approval holds only for the % it was given for). This is the condition under which BPA-61's rule says
 * NeedsRetraction, so a request exists for every Work Pack the Project Manager's task lists. Pure.
 */
public final class RetractionRules {

    /** ASSUMPTION text (plan Q7). */
    public static final String REASON_TOO_LONG = "Retraction reason can be at most 250 characters";

    private RetractionRules() {
    }

    /** {@code ofWorkpack}: the week's requests of this Work Pack, whatever their decision. */
    public static boolean needsReason(BigDecimal approvedPct, BigDecimal lastLockedPct, List<Request> ofWorkpack) {
        if (approvedPct.compareTo(lastLockedPct) >= 0) {
            return false;
        }
        return ofWorkpack.stream().noneMatch(r -> "Approved".equals(r.decision()) && r.approvedPct().compareTo(approvedPct) == 0);
    }

    /** Jira's text; the last locked % without trailing zeros (30.00 gives "30"). */
    public static String reasonRequired(BigDecimal lastLockedPct) {
        BigDecimal stripped = lastLockedPct.stripTrailingZeros();
        String prev = (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
        return "A retraction reason is required when completion falls below last week (" + prev + "%)";
    }
}
