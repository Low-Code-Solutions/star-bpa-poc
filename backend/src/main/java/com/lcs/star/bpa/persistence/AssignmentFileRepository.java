package com.lcs.star.bpa.persistence;

import com.lcs.star.bpa.api.generated.model.AssignmentFileResult;
import com.lcs.star.bpa.api.generated.model.AssignmentFileRow;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The assignment file intake log (BPA-12; data model 5.1, 5.2, 5.12): the uploaded file, every row as written (D49), and the
 * duplicate guard {@code published_message} (D73, B3). Plain statements; the caller (AssignmentFileService) owns the transaction.
 * The result is rebuilt from these tables, with the Worker's name joined from the Worker master on read (D59, B15).
 */
@Repository
public class AssignmentFileRepository {

    private final JdbcTemplate jdbc;

    public AssignmentFileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** What routing needs of a Work Pack: its status and assignment_state (null while no print exists, D50, D61). */
    public record WorkPackState(String status, String assignmentState) {
    }

    public Optional<WorkPackState> workPack(String workpackNumber) {
        return jdbc.query("SELECT status, assignment_state FROM bpa.workpack WHERE workpack_number = ?",
                (rs, n) -> new WorkPackState(rs.getString("status"), rs.getString("assignment_state")), workpackNumber).stream().findFirst();
    }

    /** True when this file content was already published for this Work Pack (D73): the duplicate guard. */
    public boolean published(String sha256, String workpackNumber) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM bpa.published_message WHERE sha256 = ? AND workpack_number = ?)", Boolean.class, sha256, workpackNumber));
    }

    /**
     * Claims the pair before the send (data model 5.12): ON CONFLICT DO NOTHING, so of two identical uploads at the same moment only one
     * gets true (the UNIQUE constraint decides). False means the pair was already published.
     */
    public boolean claim(String sha256, String workpackNumber, String username, Instant at) {
        Timestamp when = Timestamp.from(at);
        return !jdbc.queryForList("""
                INSERT INTO bpa.published_message (sha256, workpack_number, published_at, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (sha256, workpack_number) DO NOTHING
                RETURNING id""", UUID.class, sha256, workpackNumber, when, when, username, when, username).isEmpty();
    }

    /** Undoes a claim when nothing correlated (C2, D62), so a resend is accepted. */
    public void unclaim(String sha256, String workpackNumber) {
        jdbc.update("DELETE FROM bpa.published_message WHERE sha256 = ? AND workpack_number = ?", sha256, workpackNumber);
    }

    public UUID saveFile(String fileName, String sha256, String uploadedBy, Instant receivedAt, String outcome) {
        Timestamp when = Timestamp.from(receivedAt);
        return jdbc.queryForObject("""
                INSERT INTO bpa.assignment_file (file_name, sha256, uploaded_by, received_at, outcome, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", UUID.class,
                fileName, sha256, uploadedBy, when, outcome, when, uploadedBy, when, uploadedBy);
    }

    public void saveRow(UUID fileId, int rowNumber, String workpackNumber, String badgeNo, String hoursRaw, String remarks, String outcome,
            String username, Instant at) {
        Timestamp when = Timestamp.from(at);
        jdbc.update("""
                INSERT INTO bpa.assignment_file_row (assignment_file_id, row_number, workpack_number, badge_no, estimated_hours_raw, remarks,
                                                     outcome, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", fileId, rowNumber, workpackNumber, badgeNo, hoursRaw, remarks, outcome, when, username, when, username);
    }

    /** Sets the outcome of the still-Pending rows of one Work Pack in a file (the compensation when nothing correlated). */
    public void setPendingRowsOutcome(UUID fileId, String workpackNumber, String outcome) {
        jdbc.update("UPDATE bpa.assignment_file_row SET outcome = ?, updated_at = now() WHERE assignment_file_id = ? AND workpack_number = ? AND outcome = 'Pending'",
                outcome, fileId, workpackNumber);
    }

    /** The upload and its rows as the API shows them; rows in file order, a synthetic row (no row number, BPA-13) after them. */
    public Optional<AssignmentFileResult> result(UUID fileId) {
        var header = jdbc.query("SELECT file_name, received_at, outcome FROM bpa.assignment_file WHERE id = ?",
                (rs, n) -> new Object[] {rs.getString("file_name"), rs.getTimestamp("received_at").toInstant(), rs.getString("outcome")}, fileId)
                .stream().findFirst();
        if (header.isEmpty()) {
            return Optional.empty();
        }
        List<AssignmentFileRow> rows = new ArrayList<>(jdbc.query("""
                SELECT r.row_number, r.workpack_number, r.badge_no, w.name AS worker_name, r.outcome, r.wp_outcome
                FROM bpa.assignment_file_row r LEFT JOIN bpa.worker w ON w.employee_ref = r.badge_no
                WHERE r.assignment_file_id = ?
                ORDER BY (r.row_number IS NULL), r.row_number, r.id""", (rs, n) -> {
                    AssignmentFileRow row = new AssignmentFileRow();
                    int rowNumber = rs.getInt("row_number");
                    row.setRowNumber(rs.wasNull() ? null : rowNumber);
                    row.setWorkpackNumber(rs.getString("workpack_number"));
                    row.setBadgeNo(rs.getString("badge_no"));
                    row.setWorkerName(rs.getString("worker_name"));
                    row.setOutcome(rs.getString("outcome"));
                    row.setWpOutcome(rs.getString("wp_outcome"));
                    return row;
                }, fileId));
        Object[] h = header.get();
        AssignmentFileResult result = new AssignmentFileResult(fileId, (String) h[0], ((Instant) h[1]).atOffset(ZoneOffset.UTC), rows);
        result.setOutcome((String) h[2]);
        return Optional.of(result);
    }
}
