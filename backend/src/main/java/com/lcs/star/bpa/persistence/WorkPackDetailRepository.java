package com.lcs.star.bpa.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The reads behind the Work Pack detail (BPA-73; E2-23), read only: no audit, no write. Everything the Overview, Workers, Hours and Progress tabs show, the
 * history (the audit log of the Work Pack and of its child records, and the drawing-revision notices) and the process instances whose open tasks belong on a
 * Work Pack. The summary row is the list's ({@link WorkPackListRepository}).
 */
@Repository
public class WorkPackDetailRepository {

    private final JdbcTemplate jdbc;

    public WorkPackDetailRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Header(UUID id, long processInstanceKey, LocalDate completionDate) {
    }

    public record Phase(String phase, BigDecimal manhours) {
    }

    public record Material(String materialRef, String phase) {
    }

    public record Schedule(LocalDate plannedStartDate, LocalDate plannedEndDate, String scheduledBy) {
    }

    public record Worker(String badgeNo, String workerName, String role, String designation, BigDecimal estimatedHours, boolean hoursLoadedNotRemoved,
            String journalStatus, String timesheetStatus, String cbsCodeRef) {
    }

    public record HourLine(String badgeNo, LocalDate workDate, String phase, BigDecimal hours, String status, String step, String entrySource) {
    }

    public record Retraction(BigDecimal approvedPct, String reason, String requestedBy, String decision, String decisionReason, String decidedBy,
            OffsetDateTime decidedAt) {
    }

    public record Week(UUID progressId, LocalDate weekEnding, String periodStatus, BigDecimal reportedPct, BigDecimal approvedPct, String ruleResult,
            BigDecimal earnedManhours) {
    }

    /** One history item: a change of the audit log (kind Change) or a drawing-revision notice (kind Notice). */
    public record HistoryItem(String kind, OffsetDateTime at, String username, String tableName, String columnName, String oldValue, String newValue,
            String drawingRef, String revision, String recipientUsername, String channel) {
    }

    public Optional<Header> header(String workpackNumber) {
        return jdbc.query("SELECT id, process_instance_key, completion_date FROM bpa.workpack WHERE workpack_number = ?",
                (rs, n) -> new Header(rs.getObject("id", UUID.class), rs.getLong("process_instance_key"), rs.getDate("completion_date").toLocalDate()), workpackNumber)
                .stream().findFirst();
    }

    public List<Phase> phases(UUID workpackId) {
        return jdbc.query("""
                SELECT phase, manhours FROM bpa.workpack_phase WHERE workpack_id = ?
                ORDER BY array_position(ARRAY['Engineering','Cutting','Welding','Finishing'], phase)""",
                (rs, n) -> new Phase(rs.getString("phase"), rs.getBigDecimal("manhours")), workpackId);
    }

    public List<Material> materials(UUID workpackId) {
        return jdbc.query("SELECT material_ref, phase FROM bpa.workpack_material WHERE workpack_id = ? ORDER BY phase, material_ref",
                (rs, n) -> new Material(rs.getString("material_ref"), rs.getString("phase")), workpackId);
    }

    public Optional<Schedule> schedule(UUID workpackId) {
        return jdbc.query("SELECT planned_start_date, planned_end_date, scheduled_by FROM bpa.workpack_schedule WHERE workpack_id = ?",
                (rs, n) -> new Schedule(rs.getDate("planned_start_date").toLocalDate(), rs.getDate("planned_end_date").toLocalDate(), rs.getString("scheduled_by")),
                workpackId).stream().findFirst();
    }

    public Optional<OffsetDateTime> printGeneratedAt(UUID workpackId) {
        return jdbc.query("SELECT generated_at FROM bpa.workpack_print WHERE workpack_id = ?",
                (rs, n) -> rs.getObject("generated_at", OffsetDateTime.class).withOffsetSameInstant(ZoneOffset.UTC), workpackId).stream().findFirst();
    }

    /** One entry per assigned Worker, with the Worker's Hour Journal line and time sheet (both may be missing). */
    public List<Worker> workers(UUID workpackId) {
        return jdbc.query("""
                SELECT w.employee_ref, w.name, w.role, w.designation, a.estimated_hours, a.flag, jl.status AS journal_status,
                       t.submission_status, t.cbs_code_ref
                FROM bpa.worker_assignment a
                JOIN bpa.worker w ON w.id = a.worker_id
                LEFT JOIN bpa.hour_journal_header jh ON jh.workpack_id = a.workpack_id
                LEFT JOIN bpa.hour_journal_line jl ON jl.header_id = jh.id AND jl.worker_id = a.worker_id
                LEFT JOIN bpa.digital_timesheet t ON t.workpack_id = a.workpack_id AND t.worker_id = a.worker_id
                WHERE a.workpack_id = ?
                ORDER BY w.employee_ref""",
                (rs, n) -> new Worker(rs.getString("employee_ref"), rs.getString("name"), rs.getString("role"), rs.getString("designation"),
                        rs.getBigDecimal("estimated_hours"), "HoursLoadedNotRemoved".equals(rs.getString("flag")), rs.getString("journal_status"),
                        rs.getString("submission_status"), rs.getString("cbs_code_ref")), workpackId);
    }

    /** Every line of the Work Pack's time sheets, oldest work date first; a Submitted line carries the step its submission waits at (not Closed). */
    public List<HourLine> hourLines(UUID workpackId) {
        return jdbc.query("""
                SELECT w.employee_ref, l.work_date, l.phase, l.hours, l.status, l.entry_source,
                       CASE WHEN l.status = 'Submitted' AND s.stage <> 'Closed' THEN s.stage END AS step
                FROM bpa.digital_timesheet_line l
                JOIN bpa.digital_timesheet t ON t.id = l.digital_timesheet_id
                JOIN bpa.worker w ON w.id = t.worker_id
                LEFT JOIN bpa.hours_submission s ON s.id = l.hours_submission_id
                WHERE t.workpack_id = ?
                ORDER BY l.work_date, w.employee_ref, array_position(ARRAY['Engineering','Cutting','Welding','Finishing'], l.phase)""",
                (rs, n) -> new HourLine(rs.getString("employee_ref"), rs.getDate("work_date").toLocalDate(), rs.getString("phase"), rs.getBigDecimal("hours"),
                        rs.getString("status"), rs.getString("step"), rs.getString("entry_source")), workpackId);
    }

    /** One entry per week in which the Work Pack has a progress row, newest week first, with the activity's Earned man-hours of that week. */
    public List<Week> weeks(UUID workpackId) {
        return jdbc.query("""
                SELECT p.id, d.week_ending, d.status, p.reported_pct, p.approved_pct, p.rule_result, ap.earned_manhours
                FROM bpa.workpack_progress p
                JOIN bpa.progress_period d ON d.id = p.progress_period_id
                JOIN bpa.workpack w ON w.id = p.workpack_id
                LEFT JOIN bpa.activity_progress ap ON ap.activity_ref = w.activity_ref AND ap.progress_period_id = d.id
                WHERE p.workpack_id = ?
                ORDER BY d.week_ending DESC""",
                (rs, n) -> new Week(rs.getObject("id", UUID.class), rs.getDate("week_ending").toLocalDate(), rs.getString("status"), rs.getBigDecimal("reported_pct"),
                        rs.getBigDecimal("approved_pct"), rs.getString("rule_result"), rs.getBigDecimal("earned_manhours")), workpackId);
    }

    public List<Retraction> retractions(UUID progressId) {
        return jdbc.query("""
                SELECT approved_pct, reason, requested_by, decision, decision_reason, decided_by, decided_at
                FROM bpa.progress_retraction WHERE workpack_progress_id = ? ORDER BY created_at""",
                (rs, n) -> new Retraction(rs.getBigDecimal("approved_pct"), rs.getString("reason"), rs.getString("requested_by"), rs.getString("decision"),
                        rs.getString("decision_reason"), rs.getString("decided_by"), rs.getObject("decided_at", OffsetDateTime.class)), progressId);
    }

    // --- the history ------------------------------------------------------------------------------------------------------------

    /** The ids of the Work Pack's own records, so the audit log (which has no Work Pack column) can be read for all of them (ASSUMPTION, plan BPA-73 Q4). */
    private static final String RECORD_IDS = """
            WITH ids AS (
                SELECT id FROM bpa.workpack WHERE id = :w
                UNION ALL SELECT id FROM bpa.workpack_phase WHERE workpack_id = :w
                UNION ALL SELECT id FROM bpa.workpack_material WHERE workpack_id = :w
                UNION ALL SELECT id FROM bpa.workpack_schedule WHERE workpack_id = :w
                UNION ALL SELECT id FROM bpa.workpack_print WHERE workpack_id = :w
                UNION ALL SELECT id FROM bpa.release_signoff WHERE workpack_id = :w
                UNION ALL SELECT id FROM bpa.worker_assignment WHERE workpack_id = :w
                UNION ALL SELECT id FROM bpa.hour_journal_header WHERE workpack_id = :w
                UNION ALL SELECT l.id FROM bpa.hour_journal_line l JOIN bpa.hour_journal_header h ON h.id = l.header_id WHERE h.workpack_id = :w
                UNION ALL SELECT id FROM bpa.digital_timesheet WHERE workpack_id = :w
                UNION ALL SELECT l.id FROM bpa.digital_timesheet_line l JOIN bpa.digital_timesheet t ON t.id = l.digital_timesheet_id WHERE t.workpack_id = :w
                UNION ALL SELECT id FROM bpa.hours_submission WHERE workpack_id = :w
                UNION ALL SELECT d.id FROM bpa.hours_decision d JOIN bpa.hours_submission s ON s.id = d.hours_submission_id WHERE s.workpack_id = :w
                UNION ALL SELECT id FROM bpa.workpack_progress WHERE workpack_id = :w
                UNION ALL SELECT r.id FROM bpa.progress_retraction r JOIN bpa.workpack_progress p ON p.id = r.workpack_progress_id WHERE p.workpack_id = :w
            )""";

    /** One page of the history, newest first, and the total over all pages. */
    public List<HistoryItem> history(UUID workpackId, int page, int size) {
        String sql = RECORD_IDS.replace(":w", "?") + """
                SELECT * FROM (
                    SELECT 'Change' AS kind, changed_at AS at, username, table_name, column_name, old_value, new_value, id::text AS row_id,
                           NULL::text AS drawing_ref, NULL::text AS revision, NULL::text AS recipient_username, NULL::text AS channel
                    FROM bpa.audit_log WHERE record_id IN (SELECT id FROM ids)
                    UNION ALL
                    SELECT 'Notice', sent_at, NULL, NULL, NULL, NULL, NULL, id::text, drawing_ref, revision, recipient_username, channel
                    FROM bpa.notification WHERE workpack_id = ?
                ) h ORDER BY at DESC, table_name, column_name, recipient_username, row_id LIMIT ? OFFSET ?""";
        return jdbc.query(sql, (rs, n) -> new HistoryItem(rs.getString("kind"), rs.getObject("at", OffsetDateTime.class).withOffsetSameInstant(ZoneOffset.UTC),
                rs.getString("username"), rs.getString("table_name"), rs.getString("column_name"), rs.getString("old_value"), rs.getString("new_value"),
                rs.getString("drawing_ref"), rs.getString("revision"), rs.getString("recipient_username"), rs.getString("channel")),
                args(workpackId, size, (long) page * size));
    }

    public int historyTotal(UUID workpackId) {
        String sql = RECORD_IDS.replace(":w", "?") + """
                SELECT (SELECT count(*) FROM bpa.audit_log WHERE record_id IN (SELECT id FROM ids))
                     + (SELECT count(*) FROM bpa.notification WHERE workpack_id = ?)""";
        Long total = jdbc.queryForObject(sql, Long.class, args(workpackId));
        return total == null ? 0 : Math.toIntExact(total);
    }

    /** The CTE has 15 placeholders for the Work Pack id, and the notice query one more: the id goes in every one of them, then what follows (the page). */
    private static Object[] args(UUID workpackId, Object... after) {
        List<Object> all = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            all.add(workpackId);
        }
        all.addAll(List.of(after));
        return all.toArray();
    }

    // --- the process instances whose open tasks belong on the Work Pack -------------------------------------------------------------

    /** The weekly process instances of the weeks that are not Locked and in which the Work Pack has a progress row (ASSUMPTION, plan BPA-73 Q1). */
    public List<String> openWeeklyInstanceKeys(UUID workpackId) {
        return jdbc.query("""
                SELECT d.process_instance_key FROM bpa.progress_period d
                WHERE d.status <> 'Locked' AND EXISTS (SELECT 1 FROM bpa.workpack_progress p WHERE p.progress_period_id = d.id AND p.workpack_id = ?)""",
                (rs, n) -> Long.toString(rs.getLong("process_instance_key")), workpackId);
    }

}
