package com.lcs.star.bpa.persistence;

import com.lcs.star.bpa.api.generated.model.DigitalTimesheet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Digital Time sheets (BPA-15; data model 5.7): created by {@code create-digital-timesheets} and read by
 * {@code GET /api/v1/digital-timesheets}. One time sheet per Worker per Work Pack, made from each Open Project Hour Journal Line (BPA-14).
 * Project ID and WBS are derived from the Work Pack's activity (they are NOT NULL all the way back, so they always exist), the CBS code from
 * the activity's CBS assignment and the cost centre from the Worker's personal cost centre (D40); the last two are optional, so the time sheet
 * is still created when either is missing. All of them are copies taken at creation. The same transaction moves the Work Pack's
 * assignment_state from Assigning to Assigned (D61) and writes {@code audit_log} rows for what it changes (D64, 6.1).
 */
@Repository
public class DigitalTimesheetRepository {

    private static final String ASSIGNING = "Assigning";
    private static final String ASSIGNED = "Assigned";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public DigitalTimesheetRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public enum Status { DONE, WRONG_STATE, NO_LINES }

    /** What the worker needs back: whether the step could run, the state it found, and how many time sheets it created (none on a re-run). */
    public record Result(Status status, String state, int created) {
    }

    /**
     * One time sheet per Open Line of the Work Pack, then Assigning to Assigned. The key is unique(worker_id, workpack_id), so a re-run
     * inserts nothing new and writes no audit row (D65, 09 S7). The Work Pack is locked first and must be Assigning (the first run) or
     * Assigned (a re-run); any other state, or no Open Line, writes nothing (the worker then fails the job with a message). The user on the
     * time sheets and in the audit log is the Lines': the person who uploaded the assignment file (D63, BPA-22, BPA-14).
     */
    @Transactional
    public Result createFor(UUID workpackId) {
        String state = jdbc.query("SELECT assignment_state FROM bpa.workpack WHERE id = ? FOR UPDATE",
                (rs, n) -> rs.getString(1) == null ? "" : rs.getString(1), workpackId).stream().findFirst().orElse("");
        if (!ASSIGNING.equals(state) && !ASSIGNED.equals(state)) {
            return new Result(Status.WRONG_STATE, state, 0);
        }
        List<String> uploaders = jdbc.queryForList("""
                SELECT l.created_by FROM bpa.hour_journal_line l JOIN bpa.hour_journal_header h ON h.id = l.header_id
                WHERE h.workpack_id = ? AND l.status = 'Open' ORDER BY l.worker_id LIMIT 1""", String.class, workpackId);
        if (uploaders.isEmpty()) {
            // A retry after the process has gone on (BPA-13 may have closed the Lines since) must still complete when the time sheets are there (D65).
            boolean alreadyDone = ASSIGNED.equals(state) && jdbc.queryForObject(
                    "SELECT count(*) FROM bpa.digital_timesheet WHERE workpack_id = ?", Integer.class, workpackId) > 0;
            return alreadyDone ? new Result(Status.DONE, state, 0) : new Result(Status.NO_LINES, state, 0);
        }
        Instant at = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Timestamp now = Timestamp.from(at);
        int created = insertMissing(workpackId, null, now);
        String username = uploaders.get(0);
        int moved = jdbc.update("""
                UPDATE bpa.workpack SET assignment_state = 'Assigned', updated_at = ?, updated_by = ?
                WHERE id = ? AND assignment_state = 'Assigning'""", now, username, workpackId);
        if (moved == 1) {
            jdbc.update("""
                    INSERT INTO bpa.audit_log (table_name, record_id, column_name, old_value, new_value, username, changed_at)
                    VALUES ('workpack', ?, 'assignment_state', 'Assigning', 'Assigned', ?, ?)""", workpackId, username, now);
        }
        return new Result(Status.DONE, state, created);
    }

    /**
     * The insert both the first assignment and a replacement use: a time sheet for every Open Line under the Work Pack that has none, with the coding derived
     * as in the class comment. {@code forcedUser} null means each Line's own creator (the uploader of the first file, D63); a replacement passes its uploader.
     * Returns how many time sheets it created; nine audit rows each (seven when the CBS code and cost centre are blank).
     */
    private int insertMissing(UUID workpackId, String forcedUser, Timestamp now) {
        List<NewSheet> created = jdbc.query("""
                INSERT INTO bpa.digital_timesheet (worker_id, workpack_id, journal_line_id, project_ref, wbs_ref, activity_ref, cbs_code_ref,
                                                   cost_centre_ref, submission_status, created_at, created_by, updated_at, updated_by)
                SELECT l.worker_id, p.id, l.id, wb.project_ref, a.wbs_ref, p.activity_ref, c.cbs_code_ref, w.cost_centre_ref, 'Draft',
                       ?, coalesce(?, l.created_by), ?, coalesce(?, l.created_by)
                FROM bpa.hour_journal_line l
                JOIN bpa.hour_journal_header h ON h.id = l.header_id
                JOIN bpa.workpack p ON p.id = h.workpack_id
                JOIN bpa.activity a ON a.activity_ref = p.activity_ref
                JOIN bpa.wbs wb ON wb.wbs_ref = a.wbs_ref
                JOIN bpa.worker w ON w.id = l.worker_id
                LEFT JOIN bpa.cbs_activity c ON c.activity_ref = a.activity_ref
                WHERE h.workpack_id = ? AND l.status = 'Open'
                ORDER BY l.worker_id
                ON CONFLICT (worker_id, workpack_id) DO NOTHING
                RETURNING id, worker_id, workpack_id, journal_line_id, project_ref, wbs_ref, activity_ref, cbs_code_ref, cost_centre_ref,
                          submission_status, created_by""",
                (rs, n) -> new NewSheet(rs.getObject("id", UUID.class), rs.getObject("worker_id", UUID.class),
                        rs.getObject("workpack_id", UUID.class), rs.getObject("journal_line_id", UUID.class), rs.getString("project_ref"),
                        rs.getString("wbs_ref"), rs.getString("activity_ref"), rs.getString("cbs_code_ref"), rs.getString("cost_centre_ref"),
                        rs.getString("submission_status"), rs.getString("created_by")),
                now, forcedUser, now, forcedUser, workpackId);
        for (NewSheet sheet : created) {
            audit(sheet.id(), "worker_id", sheet.workerId().toString(), sheet.createdBy(), now);
            audit(sheet.id(), "workpack_id", sheet.workpackId().toString(), sheet.createdBy(), now);
            audit(sheet.id(), "journal_line_id", sheet.journalLineId().toString(), sheet.createdBy(), now);
            audit(sheet.id(), "project_ref", sheet.projectRef(), sheet.createdBy(), now);
            audit(sheet.id(), "wbs_ref", sheet.wbsRef(), sheet.createdBy(), now);
            audit(sheet.id(), "activity_ref", sheet.activityRef(), sheet.createdBy(), now);
            if (sheet.cbsCodeRef() != null) {
                audit(sheet.id(), "cbs_code_ref", sheet.cbsCodeRef(), sheet.createdBy(), now);
            }
            if (sheet.costCentreRef() != null) {
                audit(sheet.id(), "cost_centre_ref", sheet.costCentreRef(), sheet.createdBy(), now);
            }
            audit(sheet.id(), "submission_status", sheet.submissionStatus(), sheet.createdBy(), now);
        }
        return created.size();
    }

    /**
     * The time sheets follow the Lines after a replacement (BPA-13; D7, D72), idempotently, after {@code HourJournalLineRepository.syncWithAssignments}:
     * a time sheet is added for every Open Line that has none (the same insert as the first assignment); the Cancelled time sheet of a Worker who is
     * assigned again (his Line Open again) is reset to Draft; and the Draft time sheet of a Closed Line is Cancelled when it is empty, which means
     * no digital_timesheet_line with hours above 0 (the same test as "hours loaded"; ASSUMPTION, plan Q2). The coding of an existing time sheet is a
     * copy taken at creation and is not refreshed (data model 5.7). The user and time are the replacement's (D63); every change is audited (D64).
     */
    @Transactional
    public void syncWithLines(UUID workpackId, String username, Instant at) {
        Timestamp now = Timestamp.from(at.truncatedTo(ChronoUnit.MICROS));
        insertMissing(workpackId, username, now);
        List<UUID> reopened = jdbc.queryForList("""
                SELECT t.id FROM bpa.digital_timesheet t JOIN bpa.hour_journal_line l ON l.id = t.journal_line_id
                WHERE t.workpack_id = ? AND t.submission_status = 'Cancelled' AND l.status = 'Open'""", UUID.class, workpackId);
        for (UUID id : reopened) {
            jdbc.update("UPDATE bpa.digital_timesheet SET submission_status = 'Draft', updated_at = ?, updated_by = ? WHERE id = ?", now, username, id);
            audit(id, "submission_status", "Cancelled", "Draft", username, now);
        }
        List<UUID> emptyAndClosed = jdbc.queryForList("""
                SELECT t.id FROM bpa.digital_timesheet t JOIN bpa.hour_journal_line l ON l.id = t.journal_line_id
                WHERE t.workpack_id = ? AND t.submission_status = 'Draft' AND l.status = 'Closed'
                  AND NOT EXISTS (SELECT 1 FROM bpa.digital_timesheet_line d WHERE d.digital_timesheet_id = t.id AND d.hours > 0)""", UUID.class, workpackId);
        for (UUID id : emptyAndClosed) {
            jdbc.update("UPDATE bpa.digital_timesheet SET submission_status = 'Cancelled', updated_at = ?, updated_by = ? WHERE id = ?", now, username, id);
            audit(id, "submission_status", "Draft", "Cancelled", username, now);
        }
    }

    /** The time sheets of the Work Pack with this number, by Badge No; empty when it has none yet; no value when there is no such Work Pack. */
    public Optional<List<DigitalTimesheet>> findByWorkpackNumber(String workpackNumber) {
        List<UUID> workpack = jdbc.queryForList("SELECT id FROM bpa.workpack WHERE workpack_number = ?", UUID.class, workpackNumber);
        if (workpack.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(jdbc.query("""
                SELECT w.employee_ref, p.workpack_number, t.project_ref, t.wbs_ref, t.activity_ref, t.cbs_code_ref, t.cost_centre_ref,
                       t.submission_status
                FROM bpa.digital_timesheet t
                JOIN bpa.worker w ON w.id = t.worker_id
                JOIN bpa.workpack p ON p.id = t.workpack_id
                WHERE t.workpack_id = ? ORDER BY w.employee_ref""",
                (rs, n) -> {
                    DigitalTimesheet sheet = new DigitalTimesheet(rs.getString("employee_ref"), rs.getString("workpack_number"),
                            rs.getString("project_ref"), rs.getString("wbs_ref"), rs.getString("activity_ref"),
                            DigitalTimesheet.SubmissionStatusEnum.fromValue(rs.getString("submission_status")));
                    sheet.setCbsCodeRef(rs.getString("cbs_code_ref"));
                    sheet.setCostCentreRef(rs.getString("cost_centre_ref"));
                    return sheet;
                }, workpack.get(0)));
    }

    private record NewSheet(UUID id, UUID workerId, UUID workpackId, UUID journalLineId, String projectRef, String wbsRef, String activityRef,
            String cbsCodeRef, String costCentreRef, String submissionStatus, String createdBy) {
    }

    private void audit(UUID recordId, String column, String newValue, String username, Timestamp at) {
        jdbc.update("""
                INSERT INTO bpa.audit_log (table_name, record_id, column_name, old_value, new_value, username, changed_at)
                VALUES ('digital_timesheet', ?, ?, NULL, ?, ?, ?)""", recordId, column, newValue, username, at);
    }

    private void audit(UUID recordId, String column, String oldValue, String newValue, String username, Timestamp at) {
        jdbc.update("""
                INSERT INTO bpa.audit_log (table_name, record_id, column_name, old_value, new_value, username, changed_at)
                VALUES ('digital_timesheet', ?, ?, ?, ?, ?, ?)""", recordId, column, oldValue, newValue, username, at);
    }
}
