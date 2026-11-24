package com.lcs.star.bpa.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The read behind {@code GET /api/v1/workpacks} (BPA-72; E2-22): every Work Pack with its planned dates, the project of its activity (activity to WBS to
 * project), the latest Locked week's approved % and the Earned man-hours of its activity in that same week. Read only: no audit, no write. The filters
 * combine with AND; with none, every Work Pack is listed, highest number first. One query: the data is a few hundred rows at most in the POC (no paging).
 */
@Repository
public class WorkPackListRepository {

    private final JdbcTemplate jdbc;

    public WorkPackListRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The filters of the list; a null or empty value is "no filter", except {@code projects} (BPA-79): null is "not scoped" (the detail's own lookup of one number,
     * after the controller checked the project), an empty list is "no project" and gives no rows.
     */
    public record Filter(List<String> statuses, String project, LocalDate weekEnding, String search, List<String> projects) {
    }

    /** One row of the list; the null fields are the ones the Work Pack has no value for yet. */
    public record Row(String workpackNumber, String activityRef, String drawingRef, String departmentRef, String projectRef, String status, String assignmentState,
            LocalDate plannedStartDate, LocalDate plannedEndDate, BigDecimal approvedPct, LocalDate approvedWeekEnding, BigDecimal earnedManhours) {
    }

    public List<Row> list(Filter filter) {
        if (filter.projects() != null && filter.projects().isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("""
                SELECT w.workpack_number, w.activity_ref, w.drawing_ref, w.department_ref, b.project_ref, w.status, w.assignment_state,
                       s.planned_start_date, s.planned_end_date, lp.approved_pct, lp.week_ending, ap.earned_manhours
                FROM bpa.workpack w
                JOIN bpa.activity a ON a.activity_ref = w.activity_ref
                JOIN bpa.wbs b ON b.wbs_ref = a.wbs_ref
                LEFT JOIN bpa.workpack_schedule s ON s.workpack_id = w.id
                -- the latest Locked week in which the Work Pack has an approved value: an older Locked week's value is shown, with its week, when the newest
                -- Locked week has none for it (plan BPA-72 Q5; QA review)
                LEFT JOIN LATERAL (
                    SELECT p.approved_pct, d.week_ending, d.id AS period_id
                    FROM bpa.workpack_progress p JOIN bpa.progress_period d ON d.id = p.progress_period_id
                    WHERE p.workpack_id = w.id AND d.status = 'Locked' AND p.approved_pct IS NOT NULL
                    ORDER BY d.week_ending DESC LIMIT 1) lp ON true
                LEFT JOIN bpa.activity_progress ap ON ap.activity_ref = w.activity_ref AND ap.progress_period_id = lp.period_id
                WHERE true""");
        List<Object> args = new ArrayList<>();
        if (filter.statuses() != null && !filter.statuses().isEmpty()) {
            sql.append(" AND w.status IN (").append(String.join(", ", filter.statuses().stream().map(s -> "?").toList())).append(")");
            args.addAll(filter.statuses());
        }
        if (filter.projects() != null) {
            sql.append(" AND b.project_ref = ANY (?)");
            args.add(filter.projects().toArray(new String[0]));
        }
        if (filter.project() != null && !filter.project().isBlank()) {
            sql.append(" AND b.project_ref = ?");
            args.add(filter.project().trim());
        }
        if (filter.weekEnding() != null) {
            // ASSUMPTION (plan BPA-72 Q4): the Work Packs that have a progress row for that week
            sql.append(" AND EXISTS (SELECT 1 FROM bpa.workpack_progress wp JOIN bpa.progress_period pd ON pd.id = wp.progress_period_id WHERE wp.workpack_id = w.id AND pd.week_ending = ?)");
            args.add(java.sql.Date.valueOf(filter.weekEnding()));
        }
        if (filter.search() != null && !filter.search().isBlank()) {
            // ASSUMPTION: the text is part of the number, any case; % and _ in it are taken literally
            sql.append(" AND w.workpack_number ILIKE ? ESCAPE '\\'");
            args.add("%" + filter.search().trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        sql.append(" ORDER BY w.workpack_number DESC");
        return jdbc.query(sql.toString(), (rs, n) -> new Row(rs.getString("workpack_number"), rs.getString("activity_ref"), rs.getString("drawing_ref"),
                rs.getString("department_ref"), rs.getString("project_ref"), rs.getString("status"), rs.getString("assignment_state"),
                date(rs, "planned_start_date"), date(rs, "planned_end_date"), rs.getBigDecimal("approved_pct"), date(rs, "week_ending"), rs.getBigDecimal("earned_manhours")),
                args.toArray());
    }

    private static LocalDate date(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.sql.Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }
}
