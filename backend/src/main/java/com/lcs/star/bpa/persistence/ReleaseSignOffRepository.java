package com.lcs.star.bpa.persistence;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The sign-off decision of ServiceTask_RecordSignOff (BPA-9): the release_signoff row, and on approval the Work Pack's status
 * and the Header's lock (D8), all in one transaction. Audit rows per changed business column (D64, data model §6.1).
 */
@Repository
public class ReleaseSignOffRepository {

    /** What a run did, for the log. */
    public enum Outcome { APPROVED, DECLINED, ALREADY_RECORDED }

    private static final Logger LOG = LoggerFactory.getLogger(ReleaseSignOffRepository.class);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final HourJournalHeaderRepository headers;

    public ReleaseSignOffRepository(JdbcTemplate jdbc, Clock clock, HourJournalHeaderRepository headers) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.headers = headers;
    }

    /**
     * Records the decision once per user task (D65, S7): {@code task_key} is UNIQUE and the insert is ON CONFLICT DO NOTHING,
     * checked first, so a retried job changes nothing and writes no audit row. {@code reason} is stored only for a decline.
     */
    @Transactional
    public Outcome record(UUID workpackId, long taskKey, boolean approved, String reason, String username) {
        // Microseconds, as the column holds them, so the audit text and signed_off_at are the same instant (data model §6.1).
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Timestamp at = Timestamp.from(now);
        String decision = approved ? "Approved" : "Declined";
        String declineReason = approved ? null : reason.trim();
        List<UUID> inserted = jdbc.queryForList("""
                INSERT INTO bpa.release_signoff (workpack_id, task_key, decision, decline_reason, signed_off_by, signed_off_at,
                                                 created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (task_key) DO NOTHING
                RETURNING id""", UUID.class, workpackId, taskKey, decision, declineReason, username, at, at, username, at, username);
        if (inserted.isEmpty()) {
            return Outcome.ALREADY_RECORDED;
        }
        UUID id = inserted.get(0);
        audit("release_signoff", id, "workpack_id", null, workpackId.toString(), username, now);
        audit("release_signoff", id, "task_key", null, Long.toString(taskKey), username, now);
        audit("release_signoff", id, "decision", null, decision, username, now);
        if (declineReason != null) {
            audit("release_signoff", id, "decline_reason", null, declineReason, username, now);
        }
        audit("release_signoff", id, "signed_off_by", null, username, username, now);
        audit("release_signoff", id, "signed_off_at", null, now.toString(), username, now);
        if (!approved) {
            return Outcome.DECLINED;
        }
        int released = jdbc.update("UPDATE bpa.workpack SET status = 'Released', updated_at = ?, updated_by = ? WHERE id = ? AND status = 'Draft'",
                at, username, workpackId);
        if (released != 1) {
            // Only a Draft is released (D8). A fault, not a business rejection: the job fails, retries, then an incident (S7).
            throw new IllegalStateException("Work Pack " + workpackId + " is not Draft, so it cannot be released");
        }
        audit("workpack", workpackId, "status", "Draft", "Released", username, now);
        if (!headers.lock(workpackId, username)) {
            LOG.warn("Work Pack {} was approved but its Header was already locked", workpackId);
        }
        return Outcome.APPROVED;
    }

    private void audit(String table, UUID recordId, String column, String oldValue, String newValue, String username, Instant at) {
        jdbc.update("""
                INSERT INTO bpa.audit_log (table_name, record_id, column_name, old_value, new_value, username, changed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""", table, recordId, column, oldValue, newValue, username, Timestamp.from(at));
    }
}
