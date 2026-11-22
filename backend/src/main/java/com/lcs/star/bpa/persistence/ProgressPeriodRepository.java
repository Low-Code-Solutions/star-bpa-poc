package com.lcs.star.bpa.persistence;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * progress_period (BPA-58; data model §13.3.1): the weekly cut-off opens one row per week. Idempotent on the process instance key (ADR 0007, D65,
 * 09 S7); the week is unique too, so a second instance for a week that is already cut off cannot open another. One {@code audit_log} row per business
 * column (D64). Opening a row also closes that week for booking (BPA-50 rule: a row exists for the week ending).
 */
@Repository
public class ProgressPeriodRepository {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ProgressPeriodRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record Period(UUID id, LocalDate weekEnding) {
    }

    /** The week already has a progress period that another instance opened: nothing is written; the job fails with no retries. */
    public static final class WeekAlreadyOpen extends IllegalStateException {
        WeekAlreadyOpen(String message) {
            super(message);
        }
    }

    public Optional<Period> ofInstance(long processInstanceKey) {
        return jdbc.query("SELECT id, week_ending FROM bpa.progress_period WHERE process_instance_key = ?",
                (rs, n) -> new Period(rs.getObject("id", UUID.class), rs.getDate("week_ending").toLocalDate()), processInstanceKey).stream().findFirst();
    }

    /** BPA-60: the period of a week ending (its Thursday), or empty when the week has none. */
    public Optional<Period> ofWeek(LocalDate weekEnding) {
        return jdbc.query("SELECT id, week_ending FROM bpa.progress_period WHERE week_ending = ?",
                (rs, n) -> new Period(rs.getObject("id", UUID.class), rs.getDate("week_ending").toLocalDate()), Date.valueOf(weekEnding)).stream().findFirst();
    }

    /** BPA-60: the status of the period of a week ending (CutOff or Locked), or empty when the week has no period. */
    public Optional<String> statusOfWeek(LocalDate weekEnding) {
        return jdbc.queryForList("SELECT status FROM bpa.progress_period WHERE week_ending = ?", String.class, Date.valueOf(weekEnding)).stream().findFirst();
    }

    public Optional<Period> find(UUID id) {
        return jdbc.query("SELECT id, week_ending FROM bpa.progress_period WHERE id = ?",
                (rs, n) -> new Period(rs.getObject("id", UUID.class), rs.getDate("week_ending").toLocalDate()), id).stream().findFirst();
    }

    /**
     * Opens the period of {@code weekEnding}, status CutOff, for this process instance; a re-run of the same instance returns its row and writes nothing.
     * Another instance's row for the same week is {@link WeekAlreadyOpen}.
     */
    @Transactional
    public Period open(long processInstanceKey, LocalDate weekEnding, Instant cutOffAt, String username) {
        Optional<Period> existing = ofInstance(processInstanceKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM bpa.progress_period WHERE week_ending = ?)", Boolean.class,
                Date.valueOf(weekEnding)))) {
            throw new WeekAlreadyOpen("Week ending " + weekEnding.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH))
                    + " already has a progress period");
        }
        Timestamp at = Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS));
        UUID id = jdbc.queryForObject("""
                INSERT INTO bpa.progress_period (week_ending, cut_off_at, status, process_instance_key, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, 'CutOff', ?, ?, ?, ?, ?) RETURNING id""", UUID.class, Date.valueOf(weekEnding), Timestamp.from(cutOffAt), processInstanceKey, at,
                username, at, username);
        audit(id, "week_ending", weekEnding.toString(), username, at);
        audit(id, "cut_off_at", cutOffAt.toString(), username, at);
        audit(id, "status", "CutOff", username, at);
        audit(id, "process_instance_key", Long.toString(processInstanceKey), username, at);
        return new Period(id, weekEnding);
    }

    /** BPA-66: a period with its week decision columns, read with the row locked ({@link #lockState}). */
    public record State(UUID id, LocalDate weekEnding, String status, String weekApprovedBy, Timestamp weekApprovedAt, String weekDecisionReason) {
    }

    /** BPA-66: the period row locked for the rest of the transaction, so a decision and the lock (BPA-67) never interleave; empty when there is no such period. */
    public Optional<State> lockState(UUID id) {
        return jdbc.query("""
                SELECT id, week_ending, status, week_approved_by, week_approved_at, week_decision_reason FROM bpa.progress_period WHERE id = ? FOR UPDATE""",
                (rs, n) -> new State(rs.getObject("id", UUID.class), rs.getDate("week_ending").toLocalDate(), rs.getString("status"), rs.getString("week_approved_by"),
                        rs.getTimestamp("week_approved_at"), rs.getString("week_decision_reason")), id).stream().findFirst();
    }

    /**
     * BPA-66: the Project Manager approved the week: {@code week_approved_by} and {@code week_approved_at} (now, from the injected clock), one audit row per changed column
     * (D64). The same approver again writes nothing and keeps the first time (the re-run key is the values). Call in the transaction that holds {@link #lockState}.
     */
    public void recordApproval(State before, String username) {
        if (username.equals(before.weekApprovedBy()) && before.weekApprovedAt() != null) {
            return;
        }
        Timestamp at = Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS));
        jdbc.update("UPDATE bpa.progress_period SET week_approved_by = ?, week_approved_at = ?, updated_at = ?, updated_by = ? WHERE id = ?", username, at, at, username, before.id());
        audit(before.id(), "week_approved_by", before.weekApprovedBy(), username, username, at);
        audit(before.id(), "week_approved_at", before.weekApprovedAt() == null ? null : before.weekApprovedAt().toInstant().toString(), at.toInstant().toString(), username, at);
    }

    /** BPA-66: the Project Manager sent the week back: {@code week_decision_reason} is the latest reason; who and when are in {@code audit_log}. The same reason writes nothing. */
    public void recordReason(State before, String reason, String username) {
        if (reason.equals(before.weekDecisionReason())) {
            return;
        }
        Timestamp at = Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS));
        jdbc.update("UPDATE bpa.progress_period SET week_decision_reason = ?, updated_at = ?, updated_by = ? WHERE id = ?", reason, at, username, before.id());
        audit(before.id(), "week_decision_reason", before.weekDecisionReason(), reason, username, at);
    }

    private void audit(UUID recordId, String column, String oldValue, String newValue, String username, Timestamp at) {
        jdbc.update("""
                INSERT INTO bpa.audit_log (table_name, record_id, column_name, old_value, new_value, username, changed_at)
                VALUES ('progress_period', ?, ?, ?, ?, ?, ?)""", recordId, column, oldValue, newValue, username, at);
    }

    private void audit(UUID recordId, String column, String newValue, String username, Timestamp at) {
        jdbc.update("""
                INSERT INTO bpa.audit_log (table_name, record_id, column_name, old_value, new_value, username, changed_at)
                VALUES ('progress_period', ?, ?, NULL, ?, ?, ?)""", recordId, column, newValue, username, at);
    }
}
