package com.lcs.star.bpa.persistence;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * activity_progress (BPA-62; data model §13.3.6): one row per activity and week, the hours-weighted % of its Work Packs and the cumulative Earned man-hours.
 * The writes take part in the caller's transaction (EarnedManhoursService). One {@code audit_log} row per changed business column (D64). A Locked week is
 * never written here; that is the service's check.
 */
@Repository
public class ActivityProgressRepository {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ActivityProgressRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** A Released, In Progress or Completed Work Pack with its % for the week and its weight (Σ phase man-hours), and its activity's budgeted units. */
    public record Counted(String activityRef, BigDecimal budgetedUnits, BigDecimal pct, BigDecimal weight) {
    }

    /** One stored row of the week. */
    public record Stored(UUID id, String activityRef, BigDecimal actualPct, BigDecimal budgetedUnits, BigDecimal earnedManhours) {
    }

    /** One row of the week for the read endpoint, with the Earned man-hours of the latest Locked week before it. */
    public record Shown(String activityRef, String activityName, BigDecimal actualPct, BigDecimal budgetedUnits, BigDecimal earnedManhours,
            BigDecimal lastLockedEarnedManhours) {
    }

    /**
     * The Work Packs that count (status Released, In Progress or Completed; Draft is excluded) with each one's %: this week's approved %, else the approved % of the
     * latest Locked week before it, else 0 (decision (c), plan Q3). By activity, then Work Pack number.
     */
    public List<Counted> counted(UUID periodId, LocalDate weekEnding) {
        return jdbc.query("""
                SELECT w.activity_ref, a.budgeted_units,
                       COALESCE(wp.approved_pct,
                                (SELECT p2.approved_pct FROM bpa.workpack_progress p2 JOIN bpa.progress_period d2 ON d2.id = p2.progress_period_id
                                 WHERE p2.workpack_id = w.id AND d2.status = 'Locked' AND d2.week_ending < ? AND p2.approved_pct IS NOT NULL
                                 ORDER BY d2.week_ending DESC LIMIT 1), 0) AS pct,
                       COALESCE((SELECT sum(ph.manhours) FROM bpa.workpack_phase ph WHERE ph.workpack_id = w.id), 0) AS weight
                FROM bpa.workpack w
                JOIN bpa.activity a ON a.activity_ref = w.activity_ref
                LEFT JOIN bpa.workpack_progress wp ON wp.workpack_id = w.id AND wp.progress_period_id = ?
                WHERE w.status IN ('Released', 'In Progress', 'Completed')
                ORDER BY w.activity_ref, w.workpack_number""",
                (rs, n) -> new Counted(rs.getString("activity_ref"), rs.getBigDecimal("budgeted_units"), rs.getBigDecimal("pct"), rs.getBigDecimal("weight")),
                Date.valueOf(weekEnding), periodId);
    }

    /** The week's stored rows, locked for the recalculation. */
    public List<Stored> storedForUpdate(UUID periodId) {
        return jdbc.query("""
                SELECT id, activity_ref, actual_pct, budgeted_units, earned_manhours FROM bpa.activity_progress WHERE progress_period_id = ? ORDER BY activity_ref FOR UPDATE""",
                (rs, n) -> new Stored(rs.getObject("id", UUID.class), rs.getString("activity_ref"), rs.getBigDecimal("actual_pct"), rs.getBigDecimal("budgeted_units"),
                        rs.getBigDecimal("earned_manhours")), periodId);
    }

    public void insert(UUID periodId, String activityRef, BigDecimal actualPct, BigDecimal budgetedUnits, BigDecimal earned, String username) {
        Timestamp at = now();
        UUID id = jdbc.queryForObject("""
                INSERT INTO bpa.activity_progress (activity_ref, progress_period_id, actual_pct, budgeted_units, earned_manhours, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", UUID.class, activityRef, periodId, actualPct, budgetedUnits, earned, at, username, at, username);
        audit(id, "actual_pct", null, actualPct, username, at);
        audit(id, "budgeted_units", null, budgetedUnits, username, at);
        audit(id, "earned_manhours", null, earned, username, at);
    }

    /** Updates the changed columns only; a row that already holds these values is left as it is (re-run key: the values). */
    public void update(Stored old, BigDecimal actualPct, BigDecimal budgetedUnits, BigDecimal earned, String username) {
        boolean actualChanged = differs(old.actualPct(), actualPct);
        boolean unitsChanged = differs(old.budgetedUnits(), budgetedUnits);
        boolean earnedChanged = differs(old.earnedManhours(), earned);
        if (!actualChanged && !unitsChanged && !earnedChanged) {
            return;
        }
        Timestamp at = now();
        jdbc.update("UPDATE bpa.activity_progress SET actual_pct = ?, budgeted_units = ?, earned_manhours = ?, updated_at = ?, updated_by = ? WHERE id = ?", actualPct,
                budgetedUnits, earned, at, username, old.id());
        if (actualChanged) {
            audit(old.id(), "actual_pct", old.actualPct(), actualPct, username, at);
        }
        if (unitsChanged) {
            audit(old.id(), "budgeted_units", old.budgetedUnits(), budgetedUnits, username, at);
        }
        if (earnedChanged) {
            audit(old.id(), "earned_manhours", old.earnedManhours(), earned, username, at);
        }
    }

    /** Removes the row of an activity that no longer has a counted Work Pack in an unlocked week (plan Q2, ASSUMPTION); its values are audited as cleared. */
    public void delete(Stored old, String username) {
        Timestamp at = now();
        jdbc.update("DELETE FROM bpa.activity_progress WHERE id = ?", old.id());
        audit(old.id(), "actual_pct", old.actualPct(), null, username, at);
        audit(old.id(), "budgeted_units", old.budgetedUnits(), null, username, at);
        audit(old.id(), "earned_manhours", old.earnedManhours(), null, username, at);
    }

    /** The week's rows with the activity's name and the Earned man-hours of the latest Locked week before this one, by activity. */
    public List<Shown> shown(UUID periodId, LocalDate weekEnding) {
        return jdbc.query("""
                SELECT ap.activity_ref, a.activity_name, ap.actual_pct, ap.budgeted_units, ap.earned_manhours,
                       (SELECT p2.earned_manhours FROM bpa.activity_progress p2 JOIN bpa.progress_period d2 ON d2.id = p2.progress_period_id
                        WHERE p2.activity_ref = ap.activity_ref AND d2.status = 'Locked' AND d2.week_ending < ?
                        ORDER BY d2.week_ending DESC LIMIT 1) AS last_locked_earned
                FROM bpa.activity_progress ap JOIN bpa.activity a ON a.activity_ref = ap.activity_ref
                WHERE ap.progress_period_id = ? ORDER BY ap.activity_ref""",
                (rs, n) -> new Shown(rs.getString("activity_ref"), rs.getString("activity_name"), rs.getBigDecimal("actual_pct"), rs.getBigDecimal("budgeted_units"),
                        rs.getBigDecimal("earned_manhours"), rs.getBigDecimal("last_locked_earned")), Date.valueOf(weekEnding), periodId);
    }

    private static boolean differs(BigDecimal a, BigDecimal b) {
        return a == null ? b != null : b == null || a.compareTo(b) != 0;
    }

    private void audit(UUID recordId, String column, BigDecimal oldValue, BigDecimal newValue, String username, Timestamp at) {
        if (oldValue == null && newValue == null) {
            return;
        }
        jdbc.update("""
                INSERT INTO bpa.audit_log (table_name, record_id, column_name, old_value, new_value, username, changed_at)
                VALUES ('activity_progress', ?, ?, ?, ?, ?, ?)""", recordId, column, oldValue == null ? null : oldValue.toPlainString(),
                newValue == null ? null : newValue.toPlainString(), username, at);
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS));
    }
}
