package com.lcs.star.bpa.persistence;

import com.lcs.star.bpa.api.generated.model.HourJournalHeader;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Project Hour Journal Header (BPA-7; data model §5.5), simulating the D365 Project Hour Journal (Int-4.1). One per Work
 * Pack (UNIQUE workpack_id). Its estimated man hours are the sum of the Work Pack's phase man hours, kept in step while the
 * header is unlocked (D21) and never changed once locked at sign-off (D8; "skipped once locked", §5.5).
 */
@Repository
public class HourJournalHeaderRepository {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public HourJournalHeaderRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** What an upsert did, for the log. */
    public enum Outcome { CREATED, UPDATED, UNCHANGED, LOCKED }

    /**
     * Creates the Work Pack's header, or brings its estimate in line with the phases. Idempotent (D65, S7): a re-run changes
     * nothing when the sum is unchanged, and a locked header is never changed. Audit rows per business column (D64, §6.1).
     */
    @Transactional
    public Outcome upsert(UUID workpackId, String username) {
        // SUM over numeric(7,2) in the database: exact, so 150.5 + 249.5 is 400 and 0.01 stays 0.01 (BPA-7 outline).
        BigDecimal estimated = jdbc.queryForObject(
                "SELECT coalesce(sum(manhours), 0) FROM bpa.workpack_phase WHERE workpack_id = ?", BigDecimal.class, workpackId);
        Instant now = clock.instant();
        Timestamp at = Timestamp.from(now);
        List<UUID> inserted = jdbc.queryForList("""
                INSERT INTO bpa.hour_journal_header (workpack_id, estimated_manhours, locked, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, false, ?, ?, ?, ?)
                ON CONFLICT (workpack_id) DO NOTHING
                RETURNING id""", UUID.class, workpackId, estimated, at, username, at, username);
        if (!inserted.isEmpty()) {
            UUID id = inserted.get(0);
            audit(id, "workpack_id", null, workpackId.toString(), username, now);
            audit(id, "estimated_manhours", null, text(estimated), username, now);
            audit(id, "locked", null, "false", username, now);
            return Outcome.CREATED;
        }
        // The header exists: read it under a row lock, so a racing retry cannot update it twice.
        Map<String, Object> current = jdbc.queryForMap(
                "SELECT id, estimated_manhours, locked FROM bpa.hour_journal_header WHERE workpack_id = ? FOR UPDATE", workpackId);
        if (Boolean.TRUE.equals(current.get("locked"))) {
            return Outcome.LOCKED;
        }
        BigDecimal before = (BigDecimal) current.get("estimated_manhours");
        if (before.compareTo(estimated) == 0) {
            return Outcome.UNCHANGED;
        }
        UUID id = (UUID) current.get("id");
        jdbc.update("UPDATE bpa.hour_journal_header SET estimated_manhours = ?, updated_at = ?, updated_by = ? WHERE id = ?",
                estimated, at, username, id);
        audit(id, "estimated_manhours", text(before), text(estimated), username, now);
        return Outcome.UPDATED;
    }

    /**
     * Locks the Work Pack's header at sign-off (D8, BPA-9): its man hours are never changed again. Joins the caller's
     * transaction. A header must exist by now (it is created when the Work Pack is saved); a missing one is a fault, so the
     * sign-off job fails and ends as an incident (S7). Returns false when it was already locked, and changes nothing then.
     */
    @Transactional
    public boolean lock(UUID workpackId, String username) {
        Map<String, Object> current = jdbc.queryForList(
                "SELECT id, locked FROM bpa.hour_journal_header WHERE workpack_id = ? FOR UPDATE", workpackId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No Project Hour Journal Header to lock for Work Pack " + workpackId));
        if (Boolean.TRUE.equals(current.get("locked"))) {
            return false;
        }
        Instant now = clock.instant();
        UUID id = (UUID) current.get("id");
        jdbc.update("UPDATE bpa.hour_journal_header SET locked = true, updated_at = ?, updated_by = ? WHERE id = ?",
                Timestamp.from(now), username, id);
        audit(id, "locked", "false", "true", username, now);
        return true;
    }

    /** The header of the Work Pack with this number, if the Work Pack exists: empty list when it has none yet. */
    public Optional<List<HourJournalHeader>> findByWorkpackNumber(String workpackNumber) {
        List<UUID> workpack = jdbc.queryForList("SELECT id FROM bpa.workpack WHERE workpack_number = ?", UUID.class, workpackNumber);
        if (workpack.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(jdbc.query("""
                SELECT w.workpack_number, h.estimated_manhours, h.locked
                FROM bpa.hour_journal_header h JOIN bpa.workpack w ON w.id = h.workpack_id
                WHERE h.workpack_id = ?""",
                (rs, n) -> new HourJournalHeader(rs.getString("workpack_number"), rs.getBigDecimal("estimated_manhours"),
                        rs.getBoolean("locked")), workpack.get(0)));
    }

    private void audit(UUID recordId, String column, String oldValue, String newValue, String username, Instant at) {
        jdbc.update("""
                INSERT INTO bpa.audit_log (table_name, record_id, column_name, old_value, new_value, username, changed_at)
                VALUES ('hour_journal_header', ?, ?, ?, ?, ?, ?)""", recordId, column, oldValue, newValue, username, Timestamp.from(at));
    }

    /** Data model §6.1: numbers without trailing zeros. */
    private static String text(BigDecimal number) {
        return number.stripTrailingZeros().toPlainString();
    }
}
