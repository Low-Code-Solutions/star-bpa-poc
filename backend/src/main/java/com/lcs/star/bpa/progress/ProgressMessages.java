package com.lcs.star.bpa.progress;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * The messages of the progress rule (BPA-61; E2-11), pure so a unit test covers them. The texts are Jira's; the number format is an ASSUMPTION (plan Q7): a
 * number is written without trailing zeros and without an exponent ("approved 120%", "fell from 30% to 25%", "100.01", "25.5").
 * <ul>
 * <li>ABOVE_100: "&lt;WP&gt; completion cannot exceed 100% (approved &lt;n&gt;%)"</li>
 * <li>BELOW_0: "&lt;WP&gt; completion cannot be below 0%"</li>
 * <li>DECREASE: "&lt;WP&gt; completion fell from &lt;prev&gt;% to &lt;new&gt;%; a retraction is needed"</li>
 * </ul>
 * An Accepted result (no reason) has no message.
 */
public final class ProgressMessages {

    private ProgressMessages() {
    }

    /** The message for a Work Pack's result, or empty when the result has no reason (Accepted). */
    public static Optional<String> of(String workpack, String reason, BigDecimal newPct, BigDecimal previousPct) {
        if (reason == null) {
            return Optional.empty();
        }
        return switch (reason) {
            case "ABOVE_100" -> Optional.of(workpack + " completion cannot exceed 100% (approved " + number(newPct) + "%)");
            case "BELOW_0" -> Optional.of(workpack + " completion cannot be below 0%");
            case "DECREASE" -> Optional.of(workpack + " completion fell from " + number(previousPct) + "% to " + number(newPct) + "%; a retraction is needed");
            default -> Optional.empty();
        };
    }

    /** A number as plain text without trailing zeros: 120.00 → "120", 25.50 → "25.5", -5 → "-5". */
    static String number(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }
}
