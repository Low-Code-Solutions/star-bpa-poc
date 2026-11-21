package com.lcs.star.bpa.progress;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The texts of the man-hour variance alert (BPA-64; D3). The message is Jira's: "Man-hours on &lt;CBS&gt; are &lt;pct&gt;% over the Current Forecast
 * (&lt;actual&gt; of &lt;forecast&gt; MH), week ending &lt;date&gt;", the percentage and both figures with 2 decimals. The email subject is an ASSUMPTION (plan Q12;
 * Jira gives only the message). The date is written "19 Nov 2026".
 */
public final class VarianceMessages {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private VarianceMessages() {
    }

    public static String manhour(String cbs, BigDecimal pct, BigDecimal actual, BigDecimal forecast, LocalDate weekEnding) {
        return "Man-hours on " + cbs + " are " + VarianceMath.two(pct) + "% over the Current Forecast (" + VarianceMath.two(actual) + " of " + VarianceMath.two(forecast)
                + " MH), week ending " + DATE.format(weekEnding);
    }

    public static String subject(String cbs, LocalDate weekEnding) {
        return "Man-hour alert: " + cbs + ", week ending " + DATE.format(weekEnding);
    }

    public static String date(LocalDate date) {
        return DATE.format(date);
    }
}
