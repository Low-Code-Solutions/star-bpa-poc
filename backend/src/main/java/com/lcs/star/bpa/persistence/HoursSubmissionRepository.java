package com.lcs.star.bpa.persistence;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * hours_submission and hours_decision (BPA-53; data model §13.2.1, §13.2.2): the send of a Work Pack's hours to the Timekeeper and the Timekeeper's
 * decision. Both writes are one transaction each, idempotent on their re-run key (ADR 0007, D65): the send on (process instance key, round number),
 * the decision on the user task's key. One {@code audit_log} row per changed business column (D64; old value null on insert).
 */
@Repository
public class HoursSubmissionRepository {

    private static final String IN_APPROVAL = "('Timekeeper', 'ProductionSupervisor', 'Ppc')";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public HoursSubmissionRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** What a send did: the submission, and how many lines it moved to Submitted (0 on a re-run). */
    public record Sent(UUID submissionId, int roundNo, int lineCount) {
    }

    /** What a decision did, for the log. */
    public enum Outcome { VALIDATED, RETURNED, ALREADY_RECORDED }

    /** One line of a submission, for the Timekeeper's screen. */
    public record SubmissionLine(UUID id, String badgeNo, LocalDate workDate, String phase, java.math.BigDecimal hours, String status, String remarks) {
    }

    /** A submission with its lines. */
    public record Submission(UUID id, String workpackNumber, int roundNo, String stage, String submittedBy, Instant submittedAt, List<SubmissionLine> lines) {
    }

    /**
     * Sends the Work Pack's Draft and Returned lines: a submission at stage Timekeeper, round = 1 + the instance's Closed rounds, and every such line
     * Submitted and linked to it. A re-run finds the same round (it is not Closed yet): the insert does nothing, and no line is Draft or Returned any
     * more, so nothing changes and nothing is audited a second time.
     */
    @Transactional
    public Sent send(long processInstanceKey, UUID workpackId, String username) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Timestamp at = Timestamp.from(now);
        int round = 1 + jdbc.queryForObject("SELECT count(*) FROM bpa.hours_submission WHERE process_instance_key = ? AND stage = 'Closed'", Integer.class,
                processInstanceKey);
        List<UUID> inserted = jdbc.queryForList("""
                INSERT INTO bpa.hours_submission (workpack_id, process_instance_key, round_no, submitted_by, submitted_at, stage,
                                                  created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, 'Timekeeper', ?, ?, ?, ?)
                ON CONFLICT (process_instance_key, round_no) DO NOTHING
                RETURNING id""", UUID.class, workpackId, processInstanceKey, round, username, at, at, username, at, username);
        UUID id;
        if (inserted.isEmpty()) {
            id = jdbc.queryForObject("SELECT id FROM bpa.hours_submission WHERE process_instance_key = ? AND round_no = ?", UUID.class, processInstanceKey, round);
        } else {
            id = inserted.get(0);
            audit("hours_submission", id, "workpack_id", null, workpackId.toString(), username, at);
            audit("hours_submission", id, "round_no", null, Integer.toString(round), username, at);
            audit("hours_submission", id, "submitted_by", null, username, username, at);
            audit("hours_submission", id, "stage", null, "Timekeeper", username, at);
        }
        // the lines to move, with the status each leaves (for the audit rows); FOR UPDATE so a concurrent save cannot change one in between
        record Moving(UUID id, String status) {
        }
        List<Moving> moving = jdbc.query("""
                SELECT l.id, l.status FROM bpa.digital_timesheet_line l JOIN bpa.digital_timesheet t ON t.id = l.digital_timesheet_id
                WHERE t.workpack_id = ? AND l.status IN ('Draft', 'Returned') FOR UPDATE OF l""",
                (rs, n) -> new Moving(rs.getObject("id", UUID.class), rs.getString("status")), workpackId);
        for (Moving line : moving) {
            jdbc.update("UPDATE bpa.digital_timesheet_line SET status = 'Submitted', hours_submission_id = ?, updated_at = ?, updated_by = ? WHERE id = ?",
                    id, at, username, line.id());
            audit("digital_timesheet_line", line.id(), "status", line.status(), "Submitted", username, at);
            audit("digital_timesheet_line", line.id(), "hours_submission_id", null, id.toString(), username, at);
        }
        return new Sent(id, round, moving.size());
    }

    /**
     * Records the Timekeeper's decision once per user task ({@code hours_decision.task_key} is UNIQUE; checked first, so a retried job writes nothing
     * more). Validated moves the submission to stage ProductionSupervisor; Returned moves its lines to Returned and closes it with outcome Returned
     * (the table's CHECK: Closed if and only if an outcome is set).
     */
    @Transactional
    public Outcome decideAsTimekeeper(UUID submissionId, long taskKey, boolean validated, String reason, String username) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Timestamp at = Timestamp.from(now);
        String decision = validated ? "Validated" : "Returned";
        String storedReason = validated ? null : reason.trim();
        List<UUID> inserted = jdbc.queryForList("""
                INSERT INTO bpa.hours_decision (hours_submission_id, step, decision, reason, task_key, decided_by, decided_at,
                                                created_at, created_by, updated_at, updated_by)
                VALUES (?, 'Timekeeper', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (task_key) DO NOTHING
                RETURNING id""", UUID.class, submissionId, decision, storedReason, taskKey, username, at, at, username, at, username);
        if (inserted.isEmpty()) {
            return Outcome.ALREADY_RECORDED;
        }
        UUID id = inserted.get(0);
        audit("hours_decision", id, "hours_submission_id", null, submissionId.toString(), username, at);
        audit("hours_decision", id, "step", null, "Timekeeper", username, at);
        audit("hours_decision", id, "decision", null, decision, username, at);
        if (storedReason != null) {
            audit("hours_decision", id, "reason", null, storedReason, username, at);
        }
        audit("hours_decision", id, "task_key", null, Long.toString(taskKey), username, at);
        if (validated) {
            jdbc.update("UPDATE bpa.hours_submission SET stage = 'ProductionSupervisor', updated_at = ?, updated_by = ? WHERE id = ?", at, username, submissionId);
            audit("hours_submission", submissionId, "stage", "Timekeeper", "ProductionSupervisor", username, at);
            return Outcome.VALIDATED;
        }
        List<UUID> returned = jdbc.queryForList("""
                UPDATE bpa.digital_timesheet_line SET status = 'Returned', updated_at = ?, updated_by = ?
                WHERE hours_submission_id = ? AND status = 'Submitted' RETURNING id""", UUID.class, at, username, submissionId);
        for (UUID lineId : returned) {
            audit("digital_timesheet_line", lineId, "status", "Submitted", "Returned", username, at);
        }
        jdbc.update("UPDATE bpa.hours_submission SET stage = 'Closed', outcome = 'Returned', updated_at = ?, updated_by = ? WHERE id = ?", at, username, submissionId);
        audit("hours_submission", submissionId, "stage", "Timekeeper", "Closed", username, at);
        audit("hours_submission", submissionId, "outcome", null, "Returned", username, at);
        return Outcome.RETURNED;
    }

    /** The submission with its lines, by Badge No, date and phase; empty when there is none. */
    public Optional<Submission> find(UUID id) {
        var header = jdbc.query("""
                SELECT s.id, p.workpack_number, s.round_no, s.stage, s.submitted_by, s.submitted_at
                FROM bpa.hours_submission s JOIN bpa.workpack p ON p.id = s.workpack_id WHERE s.id = ?""",
                (rs, n) -> new Object[] {rs.getObject("id", UUID.class), rs.getString("workpack_number"), rs.getInt("round_no"), rs.getString("stage"),
                        rs.getString("submitted_by"), rs.getTimestamp("submitted_at").toInstant()}, id).stream().findFirst();
        if (header.isEmpty()) {
            return Optional.empty();
        }
        List<SubmissionLine> lines = new ArrayList<>(jdbc.query("""
                SELECT l.id, w.employee_ref, l.work_date, l.phase, l.hours, l.status, l.remarks
                FROM bpa.digital_timesheet_line l
                JOIN bpa.digital_timesheet t ON t.id = l.digital_timesheet_id
                JOIN bpa.worker w ON w.id = t.worker_id
                WHERE l.hours_submission_id = ?
                ORDER BY w.employee_ref, l.work_date, array_position(ARRAY['Engineering','Cutting','Welding','Finishing'], l.phase)""",
                (rs, n) -> new SubmissionLine(rs.getObject("id", UUID.class), rs.getString("employee_ref"), rs.getDate("work_date").toLocalDate(),
                        rs.getString("phase"), rs.getBigDecimal("hours"), rs.getString("status"), rs.getString("remarks")), id));
        Object[] h = header.get();
        return Optional.of(new Submission((UUID) h[0], (String) h[1], (Integer) h[2], (String) h[3], (String) h[4], (Instant) h[5], lines));
    }

    /** The stage of the submission the line was sent in; empty when the line was never sent. */
    public Optional<String> stageOfLine(UUID lineId) {
        return jdbc.queryForList("""
                SELECT s.stage FROM bpa.digital_timesheet_line l JOIN bpa.hours_submission s ON s.id = l.hours_submission_id WHERE l.id = ?""",
                String.class, lineId).stream().findFirst();
    }

    /** The Work Pack's hours are in approval while a submission of it is at an approval stage (BPA-52 plan Q4, moved here by BPA-53). */
    public boolean inApproval(UUID workpackId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM bpa.hours_submission WHERE workpack_id = ? AND stage IN " + IN_APPROVAL + ")",
                Boolean.class, workpackId));
    }

    private void audit(String table, UUID recordId, String column, String oldValue, String newValue, String username, Timestamp at) {
        jdbc.update("""
                INSERT INTO bpa.audit_log (table_name, record_id, column_name, old_value, new_value, username, changed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""", table, recordId, column, oldValue, newValue, username, at);
    }
}
