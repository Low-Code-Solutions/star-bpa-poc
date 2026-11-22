package com.lcs.star.bpa.persistence;

import com.lcs.star.bpa.domain.HourLineChecks;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Hour lines on Digital Time sheets (BPA-50; data model §5.7, §5.8, §13.1, §6.1). Plain statements and one {@code audit_log} row per changed
 * business column (D64; canonical text form, old value null on insert, new value null on delete: ASSUMPTION, §6.1 names inserts and updates). The
 * caller (HourLineService) owns the transaction and the clock; every method takes the instant it writes at.
 */
@Repository
public class DigitalTimesheetLineRepository {

    private final JdbcTemplate jdbc;

    public DigitalTimesheetLineRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A time sheet with the Worker and Work Pack it belongs to. */
    public record Sheet(UUID id, UUID workerId, String employeeRef, UUID workpackId, String workpackNumber, String submissionStatus, String workpackStatus) {
    }

    /** An hour line with its time sheet. */
    public record Line(UUID id, Sheet sheet, LocalDate workDate, String phase, BigDecimal hours, String status, String entrySource, String remarks,
            BigDecimal reportedCompletionPct, String createdBy) {
    }

    private static final String SHEET_SELECT = """
            SELECT t.id, t.worker_id, w.employee_ref, t.workpack_id, p.workpack_number, t.submission_status, p.status AS workpack_status
            FROM bpa.digital_timesheet t
            JOIN bpa.worker w ON w.id = t.worker_id
            JOIN bpa.workpack p ON p.id = t.workpack_id""";

    private static Sheet sheet(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Sheet(rs.getObject("id", UUID.class), rs.getObject("worker_id", UUID.class), rs.getString("employee_ref"),
                rs.getObject("workpack_id", UUID.class), rs.getString("workpack_number"), rs.getString("submission_status"), rs.getString("workpack_status"));
    }

    public Optional<Sheet> sheet(UUID timesheetId) {
        return jdbc.query(SHEET_SELECT + " WHERE t.id = ?", (rs, n) -> sheet(rs), timesheetId).stream().findFirst();
    }

    public Optional<Line> line(UUID lineId) {
        return jdbc.query("""
                SELECT l.id AS line_id, l.work_date, l.phase, l.hours, l.status, l.entry_source, l.remarks, l.reported_completion_pct, l.created_by,
                       t.id, t.worker_id, w.employee_ref, t.workpack_id, p.workpack_number, t.submission_status, p.status AS workpack_status
                FROM bpa.digital_timesheet_line l
                JOIN bpa.digital_timesheet t ON t.id = l.digital_timesheet_id
                JOIN bpa.worker w ON w.id = t.worker_id
                JOIN bpa.workpack p ON p.id = t.workpack_id
                WHERE l.id = ?""", (rs, n) -> line(rs), lineId).stream().findFirst();
    }

    private static Line line(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Line(rs.getObject("line_id", UUID.class), sheet(rs), rs.getDate("work_date").toLocalDate(), rs.getString("phase"), rs.getBigDecimal("hours"),
                rs.getString("status"), rs.getString("entry_source"), rs.getString("remarks"), rs.getBigDecimal("reported_completion_pct"), rs.getString("created_by"));
    }

    public List<Line> lines(UUID timesheetId) {
        return jdbc.query("""
                SELECT l.id AS line_id, l.work_date, l.phase, l.hours, l.status, l.entry_source, l.remarks, l.reported_completion_pct, l.created_by,
                       t.id, t.worker_id, w.employee_ref, t.workpack_id, p.workpack_number, t.submission_status, p.status AS workpack_status
                FROM bpa.digital_timesheet_line l
                JOIN bpa.digital_timesheet t ON t.id = l.digital_timesheet_id
                JOIN bpa.worker w ON w.id = t.worker_id
                JOIN bpa.workpack p ON p.id = t.workpack_id
                WHERE t.id = ? ORDER BY l.work_date, array_position(ARRAY['Engineering','Cutting','Welding','Finishing'], l.phase), l.created_at""",
                (rs, n) -> line(rs), timesheetId);
    }

    /** A Draft or Returned line waiting to be sent (BPA-51): what the send-time worker builds a budget check for. */
    public record SendLine(UUID workerId, String badgeNo, LocalDate workDate, String phase, String cbsCodeRef) {
    }

    /** The Work Pack's Draft and Returned lines, by Badge No, date and phase (BPMN: ServiceTask_PrepareHoursSubmission collects these). */
    public List<SendLine> sendable(UUID workpackId) {
        return jdbc.query("""
                SELECT t.worker_id, w.employee_ref, l.work_date, l.phase, t.cbs_code_ref
                FROM bpa.digital_timesheet_line l
                JOIN bpa.digital_timesheet t ON t.id = l.digital_timesheet_id
                JOIN bpa.worker w ON w.id = t.worker_id
                WHERE t.workpack_id = ? AND l.status IN ('Draft', 'Returned')
                ORDER BY w.employee_ref, l.work_date, array_position(ARRAY['Engineering','Cutting','Welding','Finishing'], l.phase)""",
                (rs, n) -> new SendLine(rs.getObject("worker_id", UUID.class), rs.getString("employee_ref"), rs.getDate("work_date").toLocalDate(),
                        rs.getString("phase"), rs.getString("cbs_code_ref")), workpackId);
    }

    /** The Work Pack's phases in the order the grid shows them. */
    public List<String> phases(UUID workpackId) {
        return jdbc.queryForList("SELECT phase FROM bpa.workpack_phase WHERE workpack_id = ? ORDER BY array_position(ARRAY['Engineering','Cutting','Welding','Finishing'], phase)",
                String.class, workpackId);
    }

    /** The phase's man hours, or empty when the phase is not on the Work Pack. */
    public Optional<BigDecimal> phaseBudget(UUID workpackId, String phase) {
        return jdbc.queryForList("SELECT manhours FROM bpa.workpack_phase WHERE workpack_id = ? AND phase = ?", BigDecimal.class, workpackId, phase).stream().findFirst();
    }

    /** True when the Worker is assigned and the assignment is not flagged HoursLoadedNotRemoved (E2-D16: no new lines for such a Worker). */
    public boolean workerCanBook(UUID workpackId, UUID workerId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM bpa.worker_assignment WHERE workpack_id = ? AND worker_id = ? AND flag IS NULL)", Boolean.class, workpackId, workerId));
    }

    public boolean weekClosed(LocalDate weekEnding) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM bpa.progress_period WHERE week_ending = ?)", Boolean.class, Date.valueOf(weekEnding)));
    }

    /** BPA-67: true when the month of the work date is locked (BPA-64's {@code hours_month_lock}; its month is the first day). */
    public boolean monthLocked(LocalDate workDate) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM bpa.hours_month_lock WHERE month = ?)", Boolean.class, Date.valueOf(workDate.withDayOfMonth(1))));
    }

    public Optional<LocalDate> latestClosedWeekEnding() {
        return jdbc.queryForList("SELECT max(week_ending) FROM bpa.progress_period", Date.class).stream().filter(d -> d != null).map(Date::toLocalDate).findFirst();
    }

    /** A line for this time sheet, date and phase that is not Rejected (E2-D4; the same rule as the partial unique index). */
    public boolean duplicate(UUID timesheetId, LocalDate date, String phase) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM bpa.digital_timesheet_line WHERE digital_timesheet_id = ? AND work_date = ? AND phase = ? AND status <> 'Rejected')""",
                Boolean.class, timesheetId, Date.valueOf(date), phase));
    }

    /** The Worker's hours on the date over all Work Packs, not Rejected, without the line being changed (E2-D6). */
    public BigDecimal workerHoursOn(UUID workerId, LocalDate date, UUID exceptLine) {
        return jdbc.queryForObject("""
                SELECT coalesce(sum(l.hours), 0) FROM bpa.digital_timesheet_line l JOIN bpa.digital_timesheet t ON t.id = l.digital_timesheet_id
                WHERE t.worker_id = ? AND l.work_date = ? AND l.status <> 'Rejected' AND l.id IS DISTINCT FROM ?""", BigDecimal.class, workerId, Date.valueOf(date), exceptLine);
    }

    /** The Work Pack's hours in the phase over all Workers, not Rejected, without the line being changed (E2-D5). */
    public BigDecimal phaseHours(UUID workpackId, String phase, UUID exceptLine) {
        return jdbc.queryForObject("""
                SELECT coalesce(sum(l.hours), 0) FROM bpa.digital_timesheet_line l JOIN bpa.digital_timesheet t ON t.id = l.digital_timesheet_id
                WHERE t.workpack_id = ? AND l.phase = ? AND l.status <> 'Rejected' AND l.id IS DISTINCT FROM ?""", BigDecimal.class, workpackId, phase, exceptLine);
    }

    /**
     * Inserts a Draft line and audits every business column; the id comes back. {@code timesheetFileId} null is a line from the form (entry source
     * Form); a time sheet upload (BPA-52) passes its file's id and the line gets entry source Upload.
     */
    public UUID insert(UUID timesheetId, LocalDate date, String phase, BigDecimal hours, String remarks, BigDecimal completion, UUID timesheetFileId,
            String username, Instant now) {
        Timestamp at = Timestamp.from(now);
        String source = timesheetFileId == null ? "Form" : "Upload";
        UUID id = jdbc.queryForObject("""
                INSERT INTO bpa.digital_timesheet_line (digital_timesheet_id, work_date, phase, hours, status, entry_source, timesheet_file_id, remarks,
                                                        reported_completion_pct, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, 'Draft', ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", UUID.class,
                timesheetId, Date.valueOf(date), phase, hours, source, timesheetFileId, remarks, completion, at, username, at, username);
        audit(id, "digital_timesheet_id", null, timesheetId.toString(), username, at);
        audit(id, "work_date", null, date.toString(), username, at);
        audit(id, "phase", null, phase, username, at);
        audit(id, "hours", null, HourLineChecks.plain(hours), username, at);
        audit(id, "status", null, "Draft", username, at);
        audit(id, "entry_source", null, source, username, at);
        if (timesheetFileId != null) {
            audit(id, "timesheet_file_id", null, timesheetFileId.toString(), username, at);
        }
        if (remarks != null) {
            audit(id, "remarks", null, remarks, username, at);
        }
        if (completion != null) {
            audit(id, "reported_completion_pct", null, HourLineChecks.plain(completion), username, at);
        }
        return id;
    }

    /** Changes hours, remarks and reported completion; one audit row per column that really changed. */
    public void update(Line old, BigDecimal hours, String remarks, BigDecimal completion, String username, Instant now) {
        Timestamp at = Timestamp.from(now);
        jdbc.update("UPDATE bpa.digital_timesheet_line SET hours = ?, remarks = ?, reported_completion_pct = ?, updated_at = ?, updated_by = ? WHERE id = ?",
                hours, remarks, completion, at, username, old.id());
        if (old.hours().compareTo(hours) != 0) {
            audit(old.id(), "hours", HourLineChecks.plain(old.hours()), HourLineChecks.plain(hours), username, at);
        }
        if (!java.util.Objects.equals(old.remarks(), remarks)) {
            audit(old.id(), "remarks", old.remarks(), remarks, username, at);
        }
        if (!sameNumber(old.reportedCompletionPct(), completion)) {
            audit(old.id(), "reported_completion_pct", old.reportedCompletionPct() == null ? null : HourLineChecks.plain(old.reportedCompletionPct()),
                    completion == null ? null : HourLineChecks.plain(completion), username, at);
        }
    }

    /** The Timekeeper's amendment (BPA-53): hours only, with an audit row for hours and one for the reason (column amend_reason; ASSUMPTION, plan Q1). */
    public void amend(Line old, BigDecimal hours, String reason, String username, Instant now) {
        Timestamp at = Timestamp.from(now);
        jdbc.update("UPDATE bpa.digital_timesheet_line SET hours = ?, updated_at = ?, updated_by = ? WHERE id = ?", hours, at, username, old.id());
        if (old.hours().compareTo(hours) != 0) {
            audit(old.id(), "hours", HourLineChecks.plain(old.hours()), HourLineChecks.plain(hours), username, at);
        }
        audit(old.id(), "amend_reason", null, reason, username, at);
    }

    /** Deletes the line; every business column is audited with a null new value (ASSUMPTION, plan). */
    public void delete(Line old, String username, Instant now) {
        Timestamp at = Timestamp.from(now);
        // BPA-53 review: an upload's result log points at the line it saved (timesheet_file_row, nullable); the log stays, without the pointer
        jdbc.update("UPDATE bpa.timesheet_file_row SET timesheet_line_id = NULL WHERE timesheet_line_id = ?", old.id());
        jdbc.update("DELETE FROM bpa.digital_timesheet_line WHERE id = ?", old.id());
        audit(old.id(), "digital_timesheet_id", old.sheet().id().toString(), null, username, at);
        audit(old.id(), "work_date", old.workDate().toString(), null, username, at);
        audit(old.id(), "phase", old.phase(), null, username, at);
        audit(old.id(), "hours", HourLineChecks.plain(old.hours()), null, username, at);
        audit(old.id(), "status", old.status(), null, username, at);
        audit(old.id(), "entry_source", old.entrySource(), null, username, at);
        if (old.remarks() != null) {
            audit(old.id(), "remarks", old.remarks(), null, username, at);
        }
        if (old.reportedCompletionPct() != null) {
            audit(old.id(), "reported_completion_pct", HourLineChecks.plain(old.reportedCompletionPct()), null, username, at);
        }
    }

    /** Released to In Progress when the first line is saved (D1); true when this call made the change (one audit row). */
    public boolean startWorkPack(UUID workpackId, String username, Instant now) {
        Timestamp at = Timestamp.from(now);
        int changed = jdbc.update("UPDATE bpa.workpack SET status = 'In Progress', updated_at = ?, updated_by = ? WHERE id = ? AND status = 'Released'",
                at, username, workpackId);
        if (changed > 0) {
            jdbc.update("""
                    INSERT INTO bpa.audit_log (table_name, record_id, column_name, old_value, new_value, username, changed_at)
                    VALUES ('workpack', ?, 'status', 'Released', 'In Progress', ?, ?)""", workpackId, username, at);
        }
        return changed > 0;
    }

    private static boolean sameNumber(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private void audit(UUID recordId, String column, String oldValue, String newValue, String username, Timestamp at) {
        jdbc.update("""
                INSERT INTO bpa.audit_log (table_name, record_id, column_name, old_value, new_value, username, changed_at)
                VALUES ('digital_timesheet_line', ?, ?, ?, ?, ?, ?)""", recordId, column, oldValue, newValue, username, at);
    }
}
