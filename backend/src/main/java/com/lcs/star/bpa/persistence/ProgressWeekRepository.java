package com.lcs.star.bpa.persistence;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The raw figures behind the Approve completion % screen (BPA-60; E2-10; data model §13.3.4): per Work Pack of the week what is stored (the report's
 * {@code reported_pct}, a stored approval and its remark) and what is derived (the last locked %, the approved hours up to the week ending, the Work Pack's
 * budget, the latest % reported by production, the FMS actual hours of the accepted report). The percentages that depend on {@code progress.basis} are worked
 * out by {@code ProgressReadService}; nothing here is stored.
 */
@Repository
public class ProgressWeekRepository {

    private final JdbcTemplate jdbc;

    public ProgressWeekRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One Work Pack of the week, as stored and as derived (nulls where the figure does not exist). */
    public record Raw(UUID workpackId, String workpackNumber, BigDecimal reportedPct, BigDecimal storedApprovedPct, String storedRemark, BigDecimal lastLockedPct,
            BigDecimal approvedHours, BigDecimal budget, BigDecimal reportedByProduction, String fmsActualHoursRaw) {
    }

    /**
     * Every In Progress Work Pack and any Work Pack that already has a progress row for the week (plan Q4), by Work Pack number. Hours count the Approved
     * lines dated on or before the week ending (plan Q1, Q10); the % reported by production is the latest non-null one on a line that is not Rejected
     * (plan Q12); the FMS actual hours are the Read row of the week's latest Accepted completion report.
     */
    public List<Raw> workpacks(UUID periodId, LocalDate weekEnding) {
        Date end = Date.valueOf(weekEnding);
        return jdbc.query("""
                SELECT w.id, w.workpack_number, wp.reported_pct, wp.approved_pct AS stored_pct, wp.override_remark,
                       COALESCE((SELECT p2.approved_pct FROM bpa.workpack_progress p2 JOIN bpa.progress_period d2 ON d2.id = p2.progress_period_id
                                 WHERE p2.workpack_id = w.id AND d2.status = 'Locked' AND d2.week_ending < ? AND p2.approved_pct IS NOT NULL
                                 ORDER BY d2.week_ending DESC LIMIT 1), 0) AS last_locked,
                       COALESCE((SELECT sum(l.hours) FROM bpa.digital_timesheet_line l JOIN bpa.digital_timesheet t ON t.id = l.digital_timesheet_id
                                 WHERE t.workpack_id = w.id AND l.status = 'Approved' AND l.work_date <= ?), 0) AS approved_hours,
                       (SELECT sum(ph.manhours) FROM bpa.workpack_phase ph WHERE ph.workpack_id = w.id) AS budget,
                       (SELECT l.reported_completion_pct FROM bpa.digital_timesheet_line l JOIN bpa.digital_timesheet t ON t.id = l.digital_timesheet_id
                        WHERE t.workpack_id = w.id AND l.reported_completion_pct IS NOT NULL AND l.status <> 'Rejected' AND l.work_date <= ?
                        ORDER BY l.work_date DESC, l.updated_at DESC LIMIT 1) AS by_production,
                       (SELECT r.actual_hours_raw FROM bpa.completion_report_row r JOIN bpa.completion_report c ON c.id = r.completion_report_id
                        WHERE c.progress_period_id = ? AND c.outcome = 'Accepted' AND r.outcome = 'Read' AND r.workpack_number = w.workpack_number
                        ORDER BY c.received_at DESC LIMIT 1) AS fms_raw
                FROM bpa.workpack w
                LEFT JOIN bpa.workpack_progress wp ON wp.workpack_id = w.id AND wp.progress_period_id = ?
                WHERE w.status = 'In Progress' OR wp.id IS NOT NULL
                ORDER BY w.workpack_number""",
                (rs, n) -> new Raw(rs.getObject("id", UUID.class), rs.getString("workpack_number"), rs.getBigDecimal("reported_pct"), rs.getBigDecimal("stored_pct"),
                        rs.getString("override_remark"), rs.getBigDecimal("last_locked"), rs.getBigDecimal("approved_hours"), rs.getBigDecimal("budget"),
                        rs.getBigDecimal("by_production"), rs.getString("fms_raw")),
                end, end, end, periodId, periodId);
    }

    /** The approved % of the Work Pack in the latest Locked week before {@code weekEnding}; 0 when it was never locked (decision (c)). */
    public BigDecimal lastLockedPct(UUID workpackId, LocalDate weekEnding) {
        return jdbc.queryForObject("""
                SELECT COALESCE((SELECT p.approved_pct FROM bpa.workpack_progress p JOIN bpa.progress_period d ON d.id = p.progress_period_id
                                 WHERE p.workpack_id = ? AND d.status = 'Locked' AND d.week_ending < ? AND p.approved_pct IS NOT NULL
                                 ORDER BY d.week_ending DESC LIMIT 1), 0)""", BigDecimal.class, workpackId, Date.valueOf(weekEnding));
    }
}
