package com.lcs.star.bpa.persistence;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * progress_retraction (BPA-63; data model §13.3.5): PPC's request to lower a Work Pack's approved % below last week's locked %, and the Project Manager's
 * decision on it. The writes take part in the caller's transaction. One {@code audit_log} row per changed business column (D64). A request is open while
 * {@code decision} is NULL; a decided request is never changed again (an Approved one is kept when PPC re-approves, BPA-61 Q4).
 */
@Repository
public class ProgressRetractionRepository {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ProgressRetractionRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** One request with the Work Pack it is for; {@code decision} is null while it is open. */
    public record Request(UUID id, UUID workpackId, String workpackNumber, String requestedBy, String reason, BigDecimal approvedPct, String decision,
            String decisionReason, Long taskKey) {
        public boolean open() {
            return decision == null;
        }
    }

    /** Every request of the week's Work Pack rows, oldest first. */
    public List<Request> ofPeriod(UUID periodId) {
        return jdbc.query("""
                SELECT r.id, p.workpack_id, w.workpack_number, r.requested_by, r.reason, r.approved_pct, r.decision, r.decision_reason, r.task_key
                FROM bpa.progress_retraction r JOIN bpa.workpack_progress p ON p.id = r.workpack_progress_id JOIN bpa.workpack w ON w.id = p.workpack_id
                WHERE p.progress_period_id = ? ORDER BY r.created_at, r.id""",
                (rs, n) -> new Request(rs.getObject("id", UUID.class), rs.getObject("workpack_id", UUID.class), rs.getString("workpack_number"),
                        rs.getString("requested_by"), rs.getString("reason"), rs.getBigDecimal("approved_pct"), rs.getString("decision"), rs.getString("decision_reason"),
                        (Long) rs.getObject("task_key")), periodId);
    }

    public Optional<UUID> progressId(UUID periodId, UUID workpackId) {
        return jdbc.queryForList("SELECT id FROM bpa.workpack_progress WHERE progress_period_id = ? AND workpack_id = ?", UUID.class, periodId, workpackId).stream().findFirst();
    }

    public void insert(UUID progressId, String requestedBy, String reason, BigDecimal approvedPct, String username) {
        Timestamp at = now();
        UUID id = jdbc.queryForObject("""
                INSERT INTO bpa.progress_retraction (workpack_progress_id, requested_by, reason, approved_pct, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", UUID.class, progressId, requestedBy, reason, approvedPct, at, username, at, username);
        audit(id, "requested_by", null, requestedBy, username, at);
        audit(id, "reason", null, reason, username, at);
        audit(id, "approved_pct", null, approvedPct.toPlainString(), username, at);
    }

    /** Changes the open request to the new reason and %; one that already holds them is left as it is. */
    public void update(Request open, String reason, BigDecimal approvedPct, String username) {
        boolean reasonChanged = !open.reason().equals(reason);
        boolean pctChanged = open.approvedPct().compareTo(approvedPct) != 0;
        if (!reasonChanged && !pctChanged) {
            return;
        }
        Timestamp at = now();
        jdbc.update("UPDATE bpa.progress_retraction SET reason = ?, approved_pct = ?, updated_at = ?, updated_by = ? WHERE id = ?", reason, approvedPct, at, username, open.id());
        if (reasonChanged) {
            audit(open.id(), "reason", open.reason(), reason, username, at);
        }
        if (pctChanged) {
            audit(open.id(), "approved_pct", open.approvedPct().toPlainString(), approvedPct.toPlainString(), username, at);
        }
    }

    /** Removes an open request that PPC no longer needs (ASSUMPTION, plan Q3); its values are audited as cleared. */
    public void delete(Request open, String username) {
        Timestamp at = now();
        jdbc.update("DELETE FROM bpa.progress_retraction WHERE id = ?", open.id());
        audit(open.id(), "requested_by", open.requestedBy(), null, username, at);
        audit(open.id(), "reason", open.reason(), null, username, at);
        audit(open.id(), "approved_pct", open.approvedPct().toPlainString(), null, username, at);
    }

    /** The Project Manager's decision on an open request; {@code taskKey} is the re-run key of the record step (UNIQUE with the Work Pack row). */
    public void decide(Request open, String decision, String decisionReason, String decidedBy, long taskKey) {
        Timestamp at = now();
        jdbc.update("""
                UPDATE bpa.progress_retraction SET decision = ?, decision_reason = ?, decided_by = ?, decided_at = ?, task_key = ?, updated_at = ?, updated_by = ?
                WHERE id = ?""", decision, decisionReason, decidedBy, at, taskKey, at, decidedBy, open.id());
        audit(open.id(), "decision", null, decision, decidedBy, at);
        audit(open.id(), "decision_reason", null, decisionReason, decidedBy, at);
        audit(open.id(), "decided_by", null, decidedBy, decidedBy, at);
    }

    private void audit(UUID recordId, String column, String oldValue, String newValue, String username, Timestamp at) {
        if (oldValue == null && newValue == null) {
            return;
        }
        jdbc.update("""
                INSERT INTO bpa.audit_log (table_name, record_id, column_name, old_value, new_value, username, changed_at)
                VALUES ('progress_retraction', ?, ?, ?, ?, ?, ?)""", recordId, column, oldValue, newValue, username, at);
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS));
    }
}
