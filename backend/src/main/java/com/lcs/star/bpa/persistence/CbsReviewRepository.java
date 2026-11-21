package com.lcs.star.bpa.persistence;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * cbs_review (BPA-64; data model §13.5.1): the Cost Controller's remark per CBS and week (FRD-22 §22.5.6.5), one row per (period, CBS). A review sent back by the
 * Project Manager (BPA-66) updates the row in place. A row that already holds the same remark and reviewer is left as it is (re-run key: the values, ADR 0007);
 * one {@code audit_log} row per changed business column (D64). A blank remark is stored as NULL.
 */
@Repository
public class CbsReviewRepository {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public CbsReviewRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** The remark of every reviewed CBS of the period (a CBS reviewed with no remark is not in the map). */
    public Map<String, String> remarks(UUID periodId) {
        Map<String, String> found = new HashMap<>();
        jdbc.query("SELECT cbs_code_ref, remark FROM bpa.cbs_review WHERE progress_period_id = ? AND remark IS NOT NULL", rs -> {
            found.put(rs.getString("cbs_code_ref"), rs.getString("remark"));
        }, periodId);
        return found;
    }

    public void upsert(UUID periodId, String cbs, String remark, String username) {
        Timestamp at = Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS));
        List<Object[]> existing = jdbc.query("SELECT id, remark, reviewed_by FROM bpa.cbs_review WHERE progress_period_id = ? AND cbs_code_ref = ? FOR UPDATE",
                (rs, n) -> new Object[] {rs.getObject("id", UUID.class), rs.getString("remark"), rs.getString("reviewed_by")}, periodId, cbs);
        if (existing.isEmpty()) {
            UUID id = jdbc.queryForObject("""
                    INSERT INTO bpa.cbs_review (progress_period_id, cbs_code_ref, remark, reviewed_by, reviewed_at, created_at, created_by, updated_at, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", UUID.class, periodId, cbs, remark, username, at, at, username, at, username);
            audit(id, "remark", null, remark, username, at);
            audit(id, "reviewed_by", null, username, username, at);
            return;
        }
        UUID id = (UUID) existing.get(0)[0];
        String oldRemark = (String) existing.get(0)[1];
        String oldBy = (String) existing.get(0)[2];
        boolean remarkChanged = !java.util.Objects.equals(oldRemark, remark);
        boolean byChanged = !username.equals(oldBy);
        if (!remarkChanged && !byChanged) {
            return;
        }
        jdbc.update("UPDATE bpa.cbs_review SET remark = ?, reviewed_by = ?, reviewed_at = ?, updated_at = ?, updated_by = ? WHERE id = ?", remark, username, at, at, username, id);
        if (remarkChanged) {
            audit(id, "remark", oldRemark, remark, username, at);
        }
        if (byChanged) {
            audit(id, "reviewed_by", oldBy, username, username, at);
        }
    }

    private void audit(UUID recordId, String column, String oldValue, String newValue, String username, Timestamp at) {
        if (oldValue == null && newValue == null) {
            return;
        }
        jdbc.update("""
                INSERT INTO bpa.audit_log (table_name, record_id, column_name, old_value, new_value, username, changed_at)
                VALUES ('cbs_review', ?, ?, ?, ?, ?, ?)""", recordId, column, oldValue, newValue, username, at);
    }
}
