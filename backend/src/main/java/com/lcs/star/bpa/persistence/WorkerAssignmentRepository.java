package com.lcs.star.bpa.persistence;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * What {@code validate-worker-assignment} reads and writes (BPA-22; data model 5.2, 5.3, 8): the rows of an uploaded file that belong to
 * one Work Pack, the Worker master, {@code worker_assignment} and the Work Pack's {@code assignment_state}. Plain statements; the caller
 * (WorkerAssignmentService) owns the transaction. Every new assignment and the state change write {@code audit_log} rows in the same
 * transaction (D64, 6.1). A row's outcome is an update of the intake log and is not audited (the log is the record; plan Q4).
 */
@Repository
public class WorkerAssignmentRepository {

    private final JdbcTemplate jdbc;

    public WorkerAssignmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A row of the uploaded file as stored (raw text), with its outcome so far ("Pending" until this step answers it). */
    public record FileRow(UUID id, int rowNumber, String badgeNo, String estimatedHoursRaw, String remarks, String outcome) {
    }

    /** The Work Pack's assignment_state, locked for this transaction so two files cannot be validated at once; empty if there is no such Work Pack. */
    public Optional<String> lockState(UUID workpackId) {
        return jdbc.query("SELECT assignment_state FROM bpa.workpack WHERE id = ? FOR UPDATE",
                (rs, n) -> rs.getString(1) == null ? "" : rs.getString(1), workpackId).stream().findFirst();
    }

    /** The file's rows for this Work Pack, in file order. */
    public List<FileRow> rows(UUID fileId, String workpackNumber) {
        return jdbc.query("""
                SELECT id, row_number, badge_no, estimated_hours_raw, remarks, outcome FROM bpa.assignment_file_row
                WHERE assignment_file_id = ? AND workpack_number = ? AND row_number IS NOT NULL ORDER BY row_number""",
                (rs, n) -> new FileRow(rs.getObject("id", UUID.class), rs.getInt("row_number"), rs.getString("badge_no"),
                        rs.getString("estimated_hours_raw"), rs.getString("remarks"), rs.getString("outcome")),
                fileId, workpackNumber);
    }

    /** The Worker master's id for each of these Badge Nos that exist (worker.employee_ref holds the Badge No, C17, D59). */
    public Map<String, UUID> workersByBadge(Collection<String> badgeNos) {
        Map<String, UUID> found = new HashMap<>();
        if (badgeNos.isEmpty()) {
            return found;
        }
        jdbc.query("SELECT employee_ref, id FROM bpa.worker WHERE employee_ref = ANY (?)", rs -> {
            found.put(rs.getString(1), rs.getObject(2, UUID.class));
        }, (Object) badgeNos.toArray(new String[0]));
        return found;
    }

    /** The Camunda username that uploaded the file (D63), if the file is known. */
    public Optional<String> uploader(UUID fileId) {
        return jdbc.queryForList("SELECT uploaded_by FROM bpa.assignment_file WHERE id = ?", String.class, fileId).stream().findFirst();
    }

    /** How many Workers of this file are assigned to the Work Pack: the answer of a retried job (D65). */
    public int assignedFrom(UUID workpackId, UUID fileId) {
        return jdbc.queryForObject("SELECT count(*) FROM bpa.worker_assignment WHERE workpack_id = ? AND assignment_file_id = ?",
                Integer.class, workpackId, fileId);
    }

    /**
     * Assigns the Worker; the key is unique(workpack_id, worker_id) (D32, 09 S7). Returns false when the Worker is already assigned
     * (a re-run), writing nothing. Otherwise one audit row per business column, old value null.
     */
    public boolean assign(UUID workpackId, UUID workerId, BigDecimal hours, String remarks, UUID fileId, String username, Instant at) {
        Timestamp now = Timestamp.from(at);
        List<UUID> inserted = jdbc.queryForList("""
                INSERT INTO bpa.worker_assignment (workpack_id, worker_id, estimated_hours, remarks, assignment_file_id,
                                                   created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (workpack_id, worker_id) DO NOTHING
                RETURNING id""", UUID.class, workpackId, workerId, hours, remarks, fileId, now, username, now, username);
        if (inserted.isEmpty()) {
            return false;
        }
        UUID id = inserted.get(0);
        audit("worker_assignment", id, "workpack_id", null, workpackId.toString(), username, now);
        audit("worker_assignment", id, "worker_id", null, workerId.toString(), username, now);
        audit("worker_assignment", id, "estimated_hours", null, hours.stripTrailingZeros().toPlainString(), username, now);
        if (remarks != null) {
            audit("worker_assignment", id, "remarks", null, remarks, username, now);
        }
        audit("worker_assignment", id, "assignment_file_id", null, fileId.toString(), username, now);
        return true;
    }

    /** The row's own result, shown by the result screen (D49). */
    public void setOutcome(UUID rowId, String outcome, String username, Instant at) {
        jdbc.update("UPDATE bpa.assignment_file_row SET outcome = ?, updated_at = ?, updated_by = ? WHERE id = ?",
                outcome, Timestamp.from(at), username, rowId);
    }

    /** AwaitingFile to Assigning when at least one row was accepted (D61, data model 8); one audit row. */
    public void setAssigning(UUID workpackId, String username, Instant at) {
        Timestamp now = Timestamp.from(at);
        int changed = jdbc.update("""
                UPDATE bpa.workpack SET assignment_state = 'Assigning', updated_at = ?, updated_by = ?
                WHERE id = ? AND assignment_state = 'AwaitingFile'""", now, username, workpackId);
        if (changed == 1) {
            audit("workpack", workpackId, "assignment_state", "AwaitingFile", "Assigning", username, now);
        }
    }

    private void audit(String table, UUID recordId, String column, String oldValue, String newValue, String username, Timestamp at) {
        jdbc.update("""
                INSERT INTO bpa.audit_log (table_name, record_id, column_name, old_value, new_value, username, changed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""", table, recordId, column, oldValue, newValue, username, at);
    }
}
