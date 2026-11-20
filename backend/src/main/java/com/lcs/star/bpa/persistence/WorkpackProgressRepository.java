package com.lcs.star.bpa.persistence;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * workpack_progress (BPA-59 writes {@code reported_pct}; data model §13.3.4): one row per Work Pack and week, {@code reported_pct} from the FMS completion
 * report (SOR FMS). The writes take part in the caller's transaction (CompletionProgressService). One {@code audit_log} row per changed business column (D64).
 * {@code approved_pct}, {@code previous_pct} and the rule result are BPA-60 and BPA-61's; they are never touched here.
 */
@Repository
public class WorkpackProgressRepository {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public WorkpackProgressRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Sets the week's reported % for each Work Pack (creating the row when the Work Pack has none yet for the week); returns the Work Packs' ids. */
    public Collection<UUID> setReported(UUID periodId, Map<String, BigDecimal> reportedByWorkpack, String username) {
        Timestamp at = now();
        List<UUID> kept = new java.util.ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : reportedByWorkpack.entrySet()) {
            UUID workpackId = jdbc.queryForObject("SELECT id FROM bpa.workpack WHERE workpack_number = ?", UUID.class, entry.getKey());
            kept.add(workpackId);
            List<Object[]> existing = jdbc.query("SELECT id, reported_pct FROM bpa.workpack_progress WHERE workpack_id = ? AND progress_period_id = ? FOR UPDATE",
                    (rs, n) -> new Object[] {rs.getObject("id", UUID.class), rs.getBigDecimal("reported_pct")}, workpackId, periodId);
            if (existing.isEmpty()) {
                UUID id = jdbc.queryForObject("""
                        INSERT INTO bpa.workpack_progress (workpack_id, progress_period_id, reported_pct, created_at, created_by, updated_at, updated_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id""", UUID.class, workpackId, periodId, entry.getValue(), at, username, at, username);
                audit(id, null, entry.getValue(), username, at);
            } else {
                UUID id = (UUID) existing.get(0)[0];
                BigDecimal old = (BigDecimal) existing.get(0)[1];
                if (old == null || old.compareTo(entry.getValue()) != 0) {
                    jdbc.update("UPDATE bpa.workpack_progress SET reported_pct = ?, updated_at = ?, updated_by = ? WHERE id = ?", entry.getValue(), at, username, id);
                    audit(id, old, entry.getValue(), username, at);
                }
            }
        }
        return kept;
    }

    /**
     * The latest accepted report wins (Q-DM20, plan Q10): a Work Pack of the week that is not in it loses its reported % while PPC has not approved a % for it
     * yet. A row already approved keeps its reported % (BPA-60's record of what was reported).
     */
    public void clearReportedExcept(UUID periodId, Collection<UUID> keptWorkpackIds, String username) {
        Timestamp at = now();
        List<Object[]> stale = jdbc.query("""
                SELECT id, workpack_id, reported_pct FROM bpa.workpack_progress
                WHERE progress_period_id = ? AND reported_pct IS NOT NULL AND approved_pct IS NULL FOR UPDATE""",
                (rs, n) -> new Object[] {rs.getObject("id", UUID.class), rs.getObject("workpack_id", UUID.class), rs.getBigDecimal("reported_pct")}, periodId);
        for (Object[] row : stale) {
            if (keptWorkpackIds.contains((UUID) row[1])) {
                continue;
            }
            jdbc.update("UPDATE bpa.workpack_progress SET reported_pct = NULL, updated_at = ?, updated_by = ? WHERE id = ?", at, username, row[0]);
            audit((UUID) row[0], (BigDecimal) row[2], null, username, at);
        }
    }

    /**
     * BPA-60: PPC's approval of the Work Pack for the week: {@code approved_pct}, {@code override_remark} (null when the approved % is the reported one),
     * {@code previous_pct} (the last locked %), {@code approved_by} and {@code approved_at}; the row is created when the Work Pack has none yet for the week.
     * A row that already holds the same approved %, remark, previous % and approver is left as it is (re-run key, ADR 0007), so {@code approved_at} is kept.
     * One audit row per changed business column (D64).
     */
    public void approve(UUID periodId, UUID workpackId, BigDecimal approvedPct, String remark, BigDecimal previousPct, String username) {
        Timestamp at = now();
        List<Object[]> existing = jdbc.query("""
                SELECT id, approved_pct, override_remark, previous_pct, approved_by FROM bpa.workpack_progress
                WHERE workpack_id = ? AND progress_period_id = ? FOR UPDATE""", (rs, n) -> new Object[] {rs.getObject("id", UUID.class), rs.getBigDecimal("approved_pct"),
                rs.getString("override_remark"), rs.getBigDecimal("previous_pct"), rs.getString("approved_by")}, workpackId, periodId);
        if (existing.isEmpty()) {
            UUID id = jdbc.queryForObject("""
                    INSERT INTO bpa.workpack_progress (workpack_id, progress_period_id, approved_pct, override_remark, previous_pct, approved_by, approved_at, created_at,
                                                       created_by, updated_at, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", UUID.class, workpackId, periodId, approvedPct, remark, previousPct, username, at, at, username, at,
                    username);
            auditColumn(id, "approved_pct", null, approvedPct.toPlainString(), username, at);
            auditColumn(id, "override_remark", null, remark, username, at);
            auditColumn(id, "previous_pct", null, previousPct.toPlainString(), username, at);
            auditColumn(id, "approved_by", null, username, username, at);
            return;
        }
        Object[] row = existing.get(0);
        UUID id = (UUID) row[0];
        BigDecimal oldApproved = (BigDecimal) row[1];
        String oldRemark = (String) row[2];
        BigDecimal oldPrevious = (BigDecimal) row[3];
        String oldBy = (String) row[4];
        boolean same = oldApproved != null && oldApproved.compareTo(approvedPct) == 0 && java.util.Objects.equals(oldRemark, remark)
                && oldPrevious != null && oldPrevious.compareTo(previousPct) == 0 && username.equals(oldBy);
        if (same) {
            return;
        }
        jdbc.update("""
                UPDATE bpa.workpack_progress SET approved_pct = ?, override_remark = ?, previous_pct = ?, approved_by = ?, approved_at = ?, updated_at = ?, updated_by = ?
                WHERE id = ?""", approvedPct, remark, previousPct, username, at, at, username, id);
        if (oldApproved == null || oldApproved.compareTo(approvedPct) != 0) {
            auditColumn(id, "approved_pct", oldApproved == null ? null : oldApproved.toPlainString(), approvedPct.toPlainString(), username, at);
        }
        if (!java.util.Objects.equals(oldRemark, remark)) {
            auditColumn(id, "override_remark", oldRemark, remark, username, at);
        }
        if (oldPrevious == null || oldPrevious.compareTo(previousPct) != 0) {
            auditColumn(id, "previous_pct", oldPrevious == null ? null : oldPrevious.toPlainString(), previousPct.toPlainString(), username, at);
        }
        if (!username.equals(oldBy)) {
            auditColumn(id, "approved_by", oldBy, username, username, at);
        }
    }

    private void auditColumn(UUID recordId, String column, String oldValue, String newValue, String username, Timestamp at) {
        if (oldValue == null && newValue == null) {
            return;
        }
        jdbc.update("""
                INSERT INTO bpa.audit_log (table_name, record_id, column_name, old_value, new_value, username, changed_at)
                VALUES ('workpack_progress', ?, ?, ?, ?, ?, ?)""", recordId, column, oldValue, newValue, username, at);
    }

    private void audit(UUID recordId, BigDecimal oldValue, BigDecimal newValue, String username, Timestamp at) {
        jdbc.update("""
                INSERT INTO bpa.audit_log (table_name, record_id, column_name, old_value, new_value, username, changed_at)
                VALUES ('workpack_progress', ?, 'reported_pct', ?, ?, ?, ?)""", recordId, oldValue == null ? null : oldValue.toPlainString(),
                newValue == null ? null : newValue.toPlainString(), username, at);
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS));
    }
}
