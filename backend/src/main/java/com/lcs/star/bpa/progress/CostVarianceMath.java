package com.lcs.star.bpa.progress;

import java.math.BigDecimal;

/**
 * The cost variance of one CBS (BPA-65; FRD-22 §22.10; Decision_CostVariance): variance % = (actual cost - revised budget) / revised budget x 100. The decision's rules, in
 * order: no revised budget (null, or 0 or less) is NO_BUDGET and never alerts; more than 10 is OVER_10_PCT (exactly 10 is no alert, D3; under the budget is no alert, an
 * over-run only); anything else is WITHIN. The comparison is made on the unrounded value (275000.01 is 10.000004%, over; shown as 10.00). The read API works the result out with
 * the same rules for the screen (plan Q10 pattern, an ASSUMPTION: a copy of the three rules); the alert itself comes from the decision. Pure.
 */
public final class CostVarianceMath {

    public static final String NO_BUDGET = "NO_BUDGET";
    public static final String OVER_10_PCT = "OVER_10_PCT";
    public static final String WITHIN = "WITHIN";

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal TEN = new BigDecimal("10");

    private CostVarianceMath() {
    }

    /** The variance % to 2 decimals half-up; null without a revised budget or without a cost. */
    public static BigDecimal variancePct(BigDecimal actual, BigDecimal budget) {
        return actual == null ? null : VarianceMath.variancePct(actual, budget);
    }

    /** NO_BUDGET, OVER_10_PCT or WITHIN, on the unrounded variance; a CBS with a budget and no cost is WITHIN (as the decision: no variance to compare). */
    public static String result(BigDecimal actual, BigDecimal budget) {
        if (!VarianceMath.hasForecast(budget)) {
            return NO_BUDGET;
        }
        if (actual == null) {
            return WITHIN;
        }
        // (actual - budget) / budget * 100 > 10, without dividing: budget is positive
        return actual.subtract(budget).multiply(HUNDRED).compareTo(budget.multiply(TEN)) > 0 ? OVER_10_PCT : WITHIN;
    }
}
