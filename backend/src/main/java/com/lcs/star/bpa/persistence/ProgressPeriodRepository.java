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

    private void audit(UUID recordId, String column, String newValue, String username, Timestamp at) {
        jdbc.update("""
                INSERT INTO bpa.audit_log (table_name, record_id, column_name, old_value, new_value, username, changed_at)
                VALUES ('progress_period', ?, ?, NULL, ?, ?, ?)""", recordId, column, newValue, username, at);
    }
}
