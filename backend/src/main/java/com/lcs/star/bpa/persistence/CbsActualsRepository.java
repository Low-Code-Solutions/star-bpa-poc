package com.lcs.star.bpa.persistence;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * What the variance check reads (BPA-64; E2-14): the cumulative Approved line hours by CBS up to a week ending (a line reaches its CBS through its time sheet,
 * {@code digital_timesheet.cbs_code_ref}; lines whose time sheet has none are the "No CBS" total, D40), and the seeded Current Forecast, Revised Budget cost and
 * actual cost by CBS (reference copies, SOR D365 F&amp;O Cost Control Report and Finance). Reads only; nothing here is stored.
 */
@Repository
public class CbsActualsRepository {

    private final JdbcTemplate jdbc;

    public CbsActualsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One CBS's seeded forecast and revised budget cost; either may be null. */
    public record Forecast(BigDecimal currentForecastManhours, BigDecimal revisedBudgetCost) {
    }

    /** Approved hours with work_date on or before the week ending, by CBS, in CBS order. Lines whose time sheet has no CBS are left out. */
    public Map<String, BigDecimal> approvedByCbs(LocalDate weekEnding) {
        Map<String, BigDecimal> found = new LinkedHashMap<>();
        jdbc.query("""
                SELECT t.cbs_code_ref, sum(l.hours) AS hours
                FROM bpa.digital_timesheet_line l JOIN bpa.digital_timesheet t ON t.id = l.digital_timesheet_id
                WHERE l.status = 'Approved' AND l.work_date <= ? AND t.cbs_code_ref IS NOT NULL
                GROUP BY t.cbs_code_ref ORDER BY t.cbs_code_ref""", rs -> {
            found.put(rs.getString("cbs_code_ref"), rs.getBigDecimal("hours"));
        }, Date.valueOf(weekEnding));
        return found;
    }

    /** The "No CBS" total (D40): Approved hours up to the week ending whose time sheet carries no CBS; 0 when none. */
    public BigDecimal approvedWithoutCbs(LocalDate weekEnding) {
        BigDecimal sum = jdbc.queryForObject("""
                SELECT coalesce(sum(l.hours), 0) FROM bpa.digital_timesheet_line l JOIN bpa.digital_timesheet t ON t.id = l.digital_timesheet_id
                WHERE l.status = 'Approved' AND l.work_date <= ? AND t.cbs_code_ref IS NULL""", BigDecimal.class, Date.valueOf(weekEnding));
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /** Every CBS that has a forecast row. */
    public Map<String, Forecast> forecasts() {
        Map<String, Forecast> found = new LinkedHashMap<>();
        jdbc.query("SELECT cbs_code_ref, current_forecast_mh, revised_budget_cost FROM bpa.cbs_forecast ORDER BY cbs_code_ref", rs -> {
            found.put(rs.getString("cbs_code_ref"), new Forecast(rs.getBigDecimal("current_forecast_mh"), rs.getBigDecimal("revised_budget_cost")));
        });
        return found;
    }

    /** The cumulative actual cost of the CBS: the sum of its months up to the week ending's month (data model "(A)"); null when it has none. */
    public BigDecimal actualCost(String cbs, LocalDate weekEnding) {
        List<BigDecimal> sums = jdbc.query("SELECT sum(actual_cost) AS cost FROM bpa.cbs_actual_cost WHERE cbs_code_ref = ? AND period_month <= ?",
                (rs, n) -> rs.getBigDecimal("cost"), cbs, Date.valueOf(weekEnding.withDayOfMonth(1)));
        return sums.isEmpty() ? null : sums.get(0);
    }
}
