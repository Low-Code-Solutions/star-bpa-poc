package com.lcs.star.bpa.persistence;

import com.lcs.star.bpa.fms.FmsHoursFileWriter.FmsRow;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * fms_hours_export and the export stamp on digital_timesheet_line (BPA-58; data model §13.3.2, §13.1). The writes take part in the caller's
 * transaction (FmsHoursExportService, RecordFmsHandOffWorker's service call): selecting the lines, storing the files and stamping the lines are one unit,
 * so a line is exported once (E2-08). One {@code audit_log} row per changed business column (D64; the file bytes are not audited).
 */
@Repository
public class FmsHoursExportRepository {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public FmsHoursExportRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** A file as a task screen lists it. */
    public record Export(UUID id, LocalDate periodMonth, int rowCount) {
    }

    /** A stored file with what the download needs. */
    public record StoredFile(UUID id, byte[] file, LocalDate weekEnding, LocalDate periodMonth) {
    }

    /** A file's hand-off state. */
    public record HandOff(UUID id, Instant sentAt, Instant rejectedAt) {
    }

    /** A line waiting for export, with the row it becomes. */
    public record WaitingLine(UUID lineId, FmsRow row) {
    }

    public List<Export> ofPeriod(UUID periodId) {
        return jdbc.query("SELECT id, period_month, row_count FROM bpa.fms_hours_export WHERE progress_period_id = ? ORDER BY period_month",
                (rs, n) -> new Export(rs.getObject("id", UUID.class), rs.getDate("period_month").toLocalDate(), rs.getInt("row_count")), periodId);
    }

    /**
     * Every Approved line not yet exported whose work date is on or before the week ending, any week (late approvals go in the next file, E2-D15), locked
     * for this transaction and in file order: Date, Badge No., Project, Activity (ASSUMPTION for the date bound, plan Q8). Cost Centre is the Worker's own
     * (plan Q9); CBS is the time sheet's, both blank when null (D40); CBS Cost Centre is the Work Pack's department (S3).
     */
    public List<WaitingLine> waitingUpTo(LocalDate weekEnding) {
        return jdbc.query("""
                SELECT l.id, l.work_date, w.employee_ref, w.agency_flag, t.project_ref, t.activity_ref, w.cost_centre_ref, t.cbs_code_ref,
                       p.department_ref, l.hours
                FROM bpa.digital_timesheet_line l
                JOIN bpa.digital_timesheet t ON t.id = l.digital_timesheet_id
                JOIN bpa.worker w ON w.id = t.worker_id
                JOIN bpa.workpack p ON p.id = t.workpack_id
                WHERE l.status = 'Approved' AND l.fms_hours_export_id IS NULL AND l.work_date <= ?
                ORDER BY l.work_date, w.employee_ref, t.project_ref, t.activity_ref, l.id
                FOR UPDATE OF l""",
                (rs, n) -> new WaitingLine(rs.getObject("id", UUID.class), new FmsRow(rs.getDate("work_date").toLocalDate(), rs.getString("employee_ref"),
                        rs.getBoolean("agency_flag"), rs.getString("project_ref"), rs.getString("activity_ref"), rs.getString("cost_centre_ref"),
                        rs.getString("cbs_code_ref"), rs.getString("department_ref"), rs.getBigDecimal("hours"))),
                Date.valueOf(weekEnding));
    }

    /** Stores one file of the period. The (period, month) key is unique (S5); a duplicate is a fault, not a second file. */
    public UUID insert(UUID periodId, LocalDate periodMonth, byte[] file, String sha256, int rowCount, String username) {
        Timestamp at = now();
        UUID id = jdbc.queryForObject("""
                INSERT INTO bpa.fms_hours_export (progress_period_id, period_month, file, sha256, row_count, generated_at, created_at, created_by, updated_at,
                                                  updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", UUID.class, periodId, Date.valueOf(periodMonth), file, sha256, rowCount, at, at, username,
                at, username);
        audit("fms_hours_export", id, "progress_period_id", null, periodId.toString(), username, at);
        audit("fms_hours_export", id, "period_month", null, periodMonth.toString(), username, at);
        audit("fms_hours_export", id, "sha256", null, sha256, username, at);
        audit("fms_hours_export", id, "row_count", null, Integer.toString(rowCount), username, at);
        return id;
    }

    /** Stamps the lines as exported in this file (exported_at, fms_hours_export_id), with their audit rows. */
    public void stamp(List<UUID> lineIds, UUID exportId, String username) {
        Timestamp at = now();
        for (UUID lineId : lineIds) {
            jdbc.update("UPDATE bpa.digital_timesheet_line SET exported_at = ?, fms_hours_export_id = ?, updated_at = ?, updated_by = ? WHERE id = ?", at, exportId,
                    at, username, lineId);
            audit("digital_timesheet_line", lineId, "exported_at", null, at.toInstant().toString(), username, at);
            audit("digital_timesheet_line", lineId, "fms_hours_export_id", null, exportId.toString(), username, at);
        }
    }

    public Optional<StoredFile> file(UUID id) {
        return jdbc.query("""
                SELECT e.id, e.file, p.week_ending, e.period_month FROM bpa.fms_hours_export e JOIN bpa.progress_period p ON p.id = e.progress_period_id
                WHERE e.id = ?""", (rs, n) -> new StoredFile(rs.getObject("id", UUID.class), rs.getBytes("file"), rs.getDate("week_ending").toLocalDate(),
                rs.getDate("period_month").toLocalDate()), id).stream().findFirst();
    }

    public Optional<HandOff> handOff(UUID id) {
        return jdbc.query("SELECT id, sent_at, fms_rejected_at FROM bpa.fms_hours_export WHERE id = ? FOR UPDATE", (rs, n) -> new HandOff(rs.getObject("id", UUID.class),
                rs.getTimestamp("sent_at") == null ? null : rs.getTimestamp("sent_at").toInstant(),
                rs.getTimestamp("fms_rejected_at") == null ? null : rs.getTimestamp("fms_rejected_at").toInstant()), id).stream().findFirst();
    }

    /** PPC handed the file to FMS: who and when (sent_by, sent_at). */
    public void recordSent(UUID id, String username) {
        Timestamp at = now();
        jdbc.update("UPDATE bpa.fms_hours_export SET sent_by = ?, sent_at = ?, updated_at = ?, updated_by = ? WHERE id = ?", username, at, at, username, id);
        audit("fms_hours_export", id, "sent_by", null, username, username, at);
        audit("fms_hours_export", id, "sent_at", null, at.toInstant().toString(), username, at);
    }

    /**
     * FMS rejected the file (S4): the rejection and its reason are stamped, the file is kept, and the file's lines are released (exported_at and
     * fms_hours_export_id cleared) so the next cut-off exports them again. Returns how many lines were released.
     */
    public int recordRejected(UUID id, String reason, String username) {
        Timestamp at = now();
        jdbc.update("UPDATE bpa.fms_hours_export SET fms_rejected_at = ?, fms_rejected_reason = ?, updated_at = ?, updated_by = ? WHERE id = ?", at, reason, at,
                username, id);
        audit("fms_hours_export", id, "fms_rejected_at", null, at.toInstant().toString(), username, at);
        audit("fms_hours_export", id, "fms_rejected_reason", null, reason, username, at);
        List<UUID> released = jdbc.queryForList("SELECT id FROM bpa.digital_timesheet_line WHERE fms_hours_export_id = ? FOR UPDATE", UUID.class, id);
        for (UUID lineId : released) {
            jdbc.update("UPDATE bpa.digital_timesheet_line SET exported_at = NULL, fms_hours_export_id = NULL, updated_at = ?, updated_by = ? WHERE id = ?", at, username,
                    lineId);
            audit("digital_timesheet_line", lineId, "fms_hours_export_id", id.toString(), null, username, at);
        }
        return released.size();
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS));
    }

    private void audit(String table, UUID recordId, String column, String oldValue, String newValue, String username, Timestamp at) {
        jdbc.update("""
                INSERT INTO bpa.audit_log (table_name, record_id, column_name, old_value, new_value, username, changed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""", table, recordId, column, oldValue, newValue, username, at);
    }
}
