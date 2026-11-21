package com.lcs.star.bpa.progress;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The man-hour variance of one CBS (BPA-64; FRD-22 §22.10; Decision_ManhourVariance): variance % = (actual − forecast) ÷ forecast × 100. The decision's rules, in
 * order: no forecast (null, or 0 or less) is NO_FORECAST and never alerts; more than 15 is OVER_15_PCT (exactly 15 is no alert, D3; under the forecast is no alert,
 * an over-run only); anything else is WITHIN. The comparison is made on the unrounded value (1150.01 is 15.001%, over; shown as 15.00). The read API works the result
 * out with the same rules, so the screen and the alert agree (plan Q10, an ASSUMPTION: a copy of the three rules). Pure.
 */
public final class VarianceMath {

    public static final String NO_FORECAST = "NO_FORECAST";
    public static final String OVER_15_PCT = "OVER_15_PCT";
    public static final String WITHIN = "WITHIN";

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal FIFTEEN = new BigDecimal("15");

    private VarianceMath() {
    }

    public static boolean hasForecast(BigDecimal forecast) {
        return forecast != null && forecast.signum() > 0;
    }

    /** The variance % to 2 decimals half-up; null without a forecast. */
    public static BigDecimal variancePct(BigDecimal actual, BigDecimal forecast) {
        if (!hasForecast(forecast)) {
            return null;
        }
        return actual.subtract(forecast).multiply(HUNDRED).divide(forecast, 2, RoundingMode.HALF_UP);
    }

    /** NO_FORECAST, OVER_15_PCT or WITHIN, on the unrounded variance. */
    public static String result(BigDecimal actual, BigDecimal forecast) {
        if (!hasForecast(forecast)) {
            return NO_FORECAST;
        }
        // (actual - forecast) / forecast * 100 > 15, without dividing: forecast is positive
        return actual.subtract(forecast).multiply(HUNDRED).compareTo(forecast.multiply(FIFTEEN)) > 0 ? OVER_15_PCT : WITHIN;
    }

    /** The estimate to complete = forecast − actual (negative when over); null without a forecast. */
    public static BigDecimal etc(BigDecimal actual, BigDecimal forecast) {
        return forecast == null ? null : forecast.subtract(actual).setScale(2, RoundingMode.HALF_UP);
    }

    /** A figure with 2 decimals, as the alert text and the API show it. */
    public static String two(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
