package com.lcs.star.bpa.persistence;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * What {@code replace-worker-assignment} reads and writes beyond BPA-22's repository (BPA-13; data model 5.2, 5.3, 5.4, 8): the Work Pack's
 * current assignments (with whether hours are loaded, D36), the change of an assignment in place, its deletion, the flag for a Worker kept
 * because hours are loaded, the history rows (D38), the extra result row for such a Worker (F1) and the Work Pack's state. Plain statements; the
 * caller (WorkerReplacementService) owns the transaction. Every change is audited in the same transaction (D64, 6.1): the old and the new value of
 * each business column that changed, with the new value null for a deleted assignment.
 */
@Repository
public class WorkerReplacementRepository {

    public static final String HOURS_LOADED_FLAG = "HoursLoadedNotRemoved";
    public static final String NOT_APPLIED = "Replacement not applied: file has rejected rows";
    public static final String HOURS_LOADED_ROW = "Hours loaded; not removed";

    private final JdbcTemplate jdbc;

    public WorkerReplacementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The Work Pack row, locked for this transaction so two replacements cannot run at once; {@code state} is empty while no print has been stored. */
    public record LockedWorkPack(UUID id, String state) {
    }

    /** A Worker's current assignment to the Work Pack; {@code hoursLoaded} is true when a digital_timesheet_line with hours above 0 exists (D36). */
    public record Assigned(UUID id, UUID workerId, String badgeNo, BigDecimal hours, String remarks, UUID fileId, String flag, boolean hoursLoaded) {
    }

    public Optional<LockedWorkPack> lock(String workpackNumber) {
        return jdbc.query("SELECT id, assignment_state FROM bpa.workpack WHERE workpack_number = ? FOR UPDATE",
                (rs, n) -> new LockedWorkPack(rs.getObject(1, UUID.class), rs.getString(2) == null ? "" : rs.getString(2)), workpackNumber).stream().findFirst();
    }

    public List<Assigned> assignments(UUID workpackId) {
        return jdbc.query("""
                SELECT a.id, a.worker_id, w.employee_ref, a.estimated_hours, a.remarks, a.assignment_file_id, a.flag,
                       EXISTS (SELECT 1 FROM bpa.digital_timesheet_line d JOIN bpa.digital_timesheet t ON t.id = d.digital_timesheet_id
                               WHERE t.workpack_id = a.workpack_id AND t.worker_id = a.worker_id AND d.hours > 0) AS loaded
                FROM bpa.worker_assignment a JOIN bpa.worker w ON w.id = a.worker_id
                WHERE a.workpack_id = ? ORDER BY w.employee_ref""",
                (rs, n) -> new Assigned(rs.getObject("id", UUID.class), rs.getObject("worker_id", UUID.class), rs.getString("employee_ref"),
                        rs.getBigDecimal("estimated_hours"), rs.getString("remarks"), rs.getObject("assignment_file_id", UUID.class),
                        rs.getString("flag"), rs.getBoolean("loaded")), workpackId);
    }

    public String fileName(UUID fileId) {
        return jdbc.queryForObject("SELECT file_name FROM bpa.assignment_file WHERE id = ?", String.class, fileId);
    }

    /** True when any row of the file for this Work Pack carries the Work Pack's "Replacement not applied" outcome: the answer of a retried job (D65). */
    public boolean wasNotApplied(UUID fileId, String workpackNumber) {
        return jdbc.queryForObject("SELECT count(*) FROM bpa.assignment_file_row WHERE assignment_file_id = ? AND workpack_number = ? AND wp_outcome IS NOT NULL",
                Integer.class, fileId, workpackNumber) > 0;
    }

    /**
     * A row of a Work Pack whose replacement is not applied (D35, D71, B1): a rejected row keeps its own outcome, a valid row has none (null), and every
     * row gets the Work Pack's message. Not audited: the intake log is the record (plan Q4).
     */
    public void markNotApplied(UUID rowId, String ownOutcome, String username, Instant at) {
        jdbc.update("UPDATE bpa.assignment_file_row SET outcome = ?, wp_outcome = ?, updated_at = ?, updated_by = ? WHERE id = ?",
                ownOutcome, NOT_APPLIED, Timestamp.from(at), username, rowId);
    }

    /** The extra row of the result for a Worker kept because hours are loaded: no row number (F1); a retry writes nothing more. */
    public void addHoursLoadedRow(UUID fileId, String workpackNumber, String badgeNo, String username, Instant at) {
        Timestamp now = Timestamp.from(at);
        jdbc.update("""
                INSERT INTO bpa.assignment_file_row (assignment_file_id, row_number, workpack_number, badge_no, outcome, created_at, created_by, updated_at, updated_by)
                VALUES (?, NULL, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (assignment_file_id, workpack_number, badge_no) WHERE row_number IS NULL DO NOTHING""",
                fileId, workpackNumber, badgeNo, HOURS_LOADED_ROW, now, username, now, username);
    }

    /** An assignment changes in place (D35): new hours and remarks, now from the replacement file; one audit row per column that changed. */
    public void change(Assigned old, BigDecimal hours, String remarks, UUID fileId, String username, Instant at) {
        Timestamp now = Timestamp.from(at);
        jdbc.update("UPDATE bpa.worker_assignment SET estimated_hours = ?, remarks = ?, assignment_file_id = ?, updated_at = ?, updated_by = ? WHERE id = ?",
                hours, remarks, fileId, now, username, old.id());
        if (old.hours().compareTo(hours) != 0) {
            audit("worker_assignment", old.id(), "estimated_hours", text(old.hours()), text(hours), username, now);
        }
        if (!java.util.Objects.equals(old.remarks(), remarks)) {
            audit("worker_assignment", old.id(), "remarks", old.remarks(), remarks, username, now);
        }
        if (!java.util.Objects.equals(old.fileId(), fileId)) {
            audit("worker_assignment", old.id(), "assignment_file_id", old.fileId().toString(), fileId.toString(), username, now);
        }
    }

    /** A removed Worker's assignment is deleted, after its history row (data model 5.3); one audit row per business column, new value null. */
    public void delete(UUID workpackId, Assigned old, String username, Instant at) {
        Timestamp now = Timestamp.from(at);
        jdbc.update("DELETE FROM bpa.worker_assignment WHERE id = ?", old.id());
        audit("worker_assignment", old.id(), "workpack_id", workpackId.toString(), null, username, now);
        audit("worker_assignment", old.id(), "worker_id", old.workerId().toString(), null, username, now);
        audit("worker_assignment", old.id(), "estimated_hours", text(old.hours()), null, username, now);
        if (old.remarks() != null) {
            audit("worker_assignment", old.id(), "remarks", old.remarks(), null, username, now);
        }
        audit("worker_assignment", old.id(), "assignment_file_id", old.fileId().toString(), null, username, now);
    }

    /** A Worker the file left out stays assigned because hours are loaded, flagged HoursLoadedNotRemoved (D36); nothing changes if already flagged (data model B14). */
    public void flagHoursLoaded(Assigned old, String username, Instant at) {
        if (old.flag() != null) {
            return;
        }
        Timestamp now = Timestamp.from(at);
        jdbc.update("UPDATE bpa.worker_assignment SET flag = ?, updated_at = ?, updated_by = ? WHERE id = ?", HOURS_LOADED_FLAG, now, username, old.id());
        audit("worker_assignment", old.id(), "flag", null, HOURS_LOADED_FLAG, username, now);
    }

    /**
     * One history row per change (D38; data model 5.4): hours only, 0 for the absent side. Unique on (assignment, change, file), so a retry writes nothing
     * more (09 S7); the audit rows are written only for a new row. {@code assignmentId} is a historical identifier, not a key.
     */
    public void addHistory(UUID assignmentId, UUID workpackId, UUID workerId, BigDecimal oldHours, BigDecimal newHours, String changeType, String sourceFile,
            UUID fileId, String username, Instant at) {
        Timestamp now = Timestamp.from(at);
        List<UUID> inserted = jdbc.queryForList("""
                INSERT INTO bpa.worker_assignment_history (worker_assignment_id, workpack_id, worker_id, old_estimated_hours, new_estimated_hours, change_type,
                                                           source_file, assignment_file_id, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (worker_assignment_id, change_type, assignment_file_id) DO NOTHING
                RETURNING id""", UUID.class, assignmentId, workpackId, workerId, oldHours, newHours, changeType, sourceFile, fileId, now, username, now, username);
        if (inserted.isEmpty()) {
            return;
        }
        UUID id = inserted.get(0);
        audit("worker_assignment_history", id, "worker_assignment_id", null, assignmentId.toString(), username, now);
        audit("worker_assignment_history", id, "workpack_id", null, workpackId.toString(), username, now);
        audit("worker_assignment_history", id, "worker_id", null, workerId.toString(), username, now);
        audit("worker_assignment_history", id, "old_estimated_hours", null, text(oldHours), username, now);
        audit("worker_assignment_history", id, "new_estimated_hours", null, text(newHours), username, now);
        audit("worker_assignment_history", id, "change_type", null, changeType, username, now);
        audit("worker_assignment_history", id, "source_file", null, sourceFile, username, now);
        audit("worker_assignment_history", id, "assignment_file_id", null, fileId.toString(), username, now);
    }

    /** Replacing back to Assigned on both outcomes (D61), with one audit row. */
    public void backToAssigned(UUID workpackId, String username, Instant at) {
        Timestamp now = Timestamp.from(at);
        int moved = jdbc.update("UPDATE bpa.workpack SET assignment_state = 'Assigned', updated_at = ?, updated_by = ? WHERE id = ? AND assignment_state = 'Replacing'",
                now, username, workpackId);
        if (moved == 1) {
            audit("workpack", workpackId, "assignment_state", "Replacing", "Assigned", username, now);
        }
    }

    /** Data model 6.1: numbers without trailing zeros. */
    private static String text(BigDecimal number) {
        return number.stripTrailingZeros().toPlainString();
    }

    private void audit(String table, UUID recordId, String column, String oldValue, String newValue, String username, Timestamp at) {
        jdbc.update("""
                INSERT INTO bpa.audit_log (table_name, record_id, column_name, old_value, new_value, username, changed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""", table, recordId, column, oldValue, newValue, username, at);
    }
}
