package com.lcs.star.bpa.persistence;

import com.lcs.star.bpa.api.generated.model.TimesheetFileResult;
import com.lcs.star.bpa.api.generated.model.TimesheetFileRow;
import com.lcs.star.bpa.timesheet.TimesheetFileReader;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The time sheet upload intake log (BPA-52; data model §13.2.3, D49 pattern): the uploaded file with its sha256 and every row as written (raw text, so
 * invalid rows can be logged), plus the small reads the row pipeline needs that the line repository does not have. Plain statements; each is its own
 * transaction, as the lines the rows save (a later row sees the earlier ones, E2-D4). Not audited: it is itself a log (data model §6.1).
 */
@Repository
public class TimesheetFileRepository {

    private final JdbcTemplate jdbc;

    public TimesheetFileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insertFile(String fileName, String sha256, String username, Instant receivedAt, String outcome) {
        Timestamp at = Timestamp.from(receivedAt);
        return jdbc.queryForObject("""
                INSERT INTO bpa.timesheet_file (file_name, sha256, uploaded_by, received_at, outcome, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", UUID.class, fileName, sha256, username, at, outcome, at, username, at, username);
    }

    /** One logged row: the values as uploaded, the outcome, and the saved line's id when the row was saved. */
    public void insertRow(UUID fileId, TimesheetFileReader.Row row, String outcome, UUID lineId, String username, Instant at) {
        Timestamp when = Timestamp.from(at);
        jdbc.update("""
                INSERT INTO bpa.timesheet_file_row (timesheet_file_id, row_number, workpack_number, badge_no, work_date_raw, phase_raw, hours_raw, remarks,
                                                    outcome, timesheet_line_id, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", fileId, row.rowNumber(), row.workpackNumber(), row.badgeNo(), row.workDate(), row.phase(),
                row.hours(), row.remarks(), outcome, lineId, when, username, when, username);
    }

    public Optional<UUID> workPackId(String workpackNumber) {
        return jdbc.queryForList("SELECT id FROM bpa.workpack WHERE workpack_number = ?", UUID.class, workpackNumber).stream().findFirst();
    }

    /** The Worker's time sheet on the Work Pack, found by the Badge No (worker.employee_ref, D59), or empty when the Worker has none there. */
    public Optional<UUID> timesheetOf(UUID workpackId, String badgeNo) {
        return jdbc.queryForList("""
                SELECT t.id FROM bpa.digital_timesheet t JOIN bpa.worker w ON w.id = t.worker_id
                WHERE t.workpack_id = ? AND w.employee_ref = ?""", UUID.class, workpackId, badgeNo).stream().findFirst();
    }

    public Optional<TimesheetFileResult> result(UUID fileId) {
        var header = jdbc.query("SELECT file_name, received_at, outcome FROM bpa.timesheet_file WHERE id = ?",
                (rs, n) -> new Object[] {rs.getString("file_name"), rs.getTimestamp("received_at").toInstant(), rs.getString("outcome")}, fileId)
                .stream().findFirst();
        if (header.isEmpty()) {
            return Optional.empty();
        }
        List<TimesheetFileRow> rows = jdbc.query("""
                SELECT row_number, workpack_number, badge_no, work_date_raw, phase_raw, hours_raw, remarks, outcome
                FROM bpa.timesheet_file_row WHERE timesheet_file_id = ? ORDER BY row_number, id""", (rs, n) -> {
                    TimesheetFileRow row = new TimesheetFileRow();
                    row.setRowNumber(rs.getInt("row_number"));
                    row.setWorkpackNumber(rs.getString("workpack_number"));
                    row.setBadgeNo(rs.getString("badge_no"));
                    row.setWorkDate(rs.getString("work_date_raw"));
                    row.setPhase(rs.getString("phase_raw"));
                    row.setHours(rs.getString("hours_raw"));
                    row.setRemarks(rs.getString("remarks"));
                    row.setOutcome(rs.getString("outcome"));
                    return row;
                }, fileId);
        Object[] h = header.get();
        TimesheetFileResult result = new TimesheetFileResult(fileId, (String) h[0], ((Instant) h[1]).atOffset(ZoneOffset.UTC), rows);
        result.setOutcome((String) h[2]);
        return Optional.of(result);
    }
}
