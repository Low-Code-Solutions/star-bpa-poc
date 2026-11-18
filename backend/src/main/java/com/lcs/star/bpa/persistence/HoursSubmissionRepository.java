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
    public enum Outcome { VALIDATED, APPROVED, RETURNED, REJECTED, ALREADY_RECORDED }

    /** The submission is not at the step that decides on it (BPA-56): nothing is written; the job fails with no retries. */
    public static final class WrongStage extends IllegalStateException {
        WrongStage(String message) {
            super(message);
        }
    }

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

    /** BPA-53's Timekeeper decision; kept for its callers, the same as {@code decide("Timekeeper", ...)}. Transactional itself: a call inside the bean skips the proxy. */
    @Transactional
    public Outcome decideAsTimekeeper(UUID submissionId, long taskKey, boolean validated, String reason, String username) {
        return decide("Timekeeper", submissionId, taskKey, validated ? "Validated" : "Returned", reason, username);
    }

    /**
     * Records a decision at an approval step once per user task ({@code hours_decision.task_key} is UNIQUE; checked first, so a retried job writes nothing
     * more), in one transaction (BPA-53, BPA-56):
     * <ul>
     * <li>Timekeeper Validated: stage ProductionSupervisor. ProductionSupervisor Approved: stage Ppc. The lines stay Submitted.</li>
     * <li>Returned (any step): the lines go back to Returned; the submission is Closed with outcome Returned.</li>
     * <li>Rejected (ProductionSupervisor): the lines become Rejected for good (never resent, never counted, BPA-48 and BPA-51); Closed with outcome Rejected.</li>
     * </ul>
     * Every stage change is guarded by {@code WHERE stage = <step>}: a submission that is not at the step throws {@link WrongStage} and the transaction
     * (the decision row included) rolls back. The table's CHECKs hold the rest: Closed if and only if an outcome is set; Validated only at Timekeeper;
     * Rejected never at Timekeeper; a reason for Returned and Rejected.
     */
    @Transactional
    public Outcome decide(String step, UUID submissionId, long taskKey, String decision, String reason, String username) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Timestamp at = Timestamp.from(now);
        boolean closes = "Returned".equals(decision) || "Rejected".equals(decision);
        String storedReason = closes ? reason.trim() : null;
        List<UUID> inserted = jdbc.queryForList("""
                INSERT INTO bpa.hours_decision (hours_submission_id, step, decision, reason, task_key, decided_by, decided_at,
                                                created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (task_key) DO NOTHING
                RETURNING id""", UUID.class, submissionId, step, decision, storedReason, taskKey, username, at, at, username, at, username);
        if (inserted.isEmpty()) {
            return Outcome.ALREADY_RECORDED;
        }
        UUID id = inserted.get(0);
        audit("hours_decision", id, "hours_submission_id", null, submissionId.toString(), username, at);
        audit("hours_decision", id, "step", null, step, username, at);
        audit("hours_decision", id, "decision", null, decision, username, at);
        if (storedReason != null) {
            audit("hours_decision", id, "reason", null, storedReason, username, at);
        }
        audit("hours_decision", id, "task_key", null, Long.toString(taskKey), username, at);
        if (!closes) {
            String next = "Timekeeper".equals(step) ? "ProductionSupervisor" : "Ppc";
            moveStage(submissionId, step, next, null, username, at);
            return "Validated".equals(decision) ? Outcome.VALIDATED : Outcome.APPROVED;
        }
        moveStage(submissionId, step, "Closed", decision, username, at);
        List<UUID> moved = jdbc.queryForList("""
                UPDATE bpa.digital_timesheet_line SET status = ?, updated_at = ?, updated_by = ?
                WHERE hours_submission_id = ? AND status = 'Submitted' RETURNING id""", UUID.class, decision, at, username, submissionId);
        for (UUID lineId : moved) {
            audit("digital_timesheet_line", lineId, "status", "Submitted", decision, username, at);
        }
        return "Returned".equals(decision) ? Outcome.RETURNED : Outcome.REJECTED;
    }

    /** Moves the submission from {@code step} to {@code next} (with the outcome when it closes), or throws {@link WrongStage} when it is not at that step. */
    private void moveStage(UUID submissionId, String step, String next, String outcome, String username, Timestamp at) {
        int changed = jdbc.update("UPDATE bpa.hours_submission SET stage = ?, outcome = ?, updated_at = ?, updated_by = ? WHERE id = ? AND stage = ?",
                next, outcome, at, username, submissionId, step);
        if (changed == 0) {
            throw new WrongStage("Submission " + submissionId + " is not at stage " + step + "; the " + step + " decision is not recorded");
        }
        audit("hours_submission", submissionId, "stage", step, next, username, at);
        if (outcome != null) {
            audit("hours_submission", submissionId, "outcome", null, outcome, username, at);
        }
    }

    /** Who sent the submission (segregation of duties, BPA-56); empty when there is no such submission. */
    public Optional<String> submittedBy(UUID submissionId) {
        return jdbc.queryForList("SELECT submitted_by FROM bpa.hours_submission WHERE id = ?", String.class, submissionId).stream().findFirst();
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
