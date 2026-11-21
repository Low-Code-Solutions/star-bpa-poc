package com.lcs.star.bpa.progress;

import com.lcs.star.bpa.config.AlertSettings;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The month lock and the frozen man-hour snapshot (BPA-64; D5; FRD-22 §22.8.1; plan Q1 option A). {@link MonthLockJob} calls {@link #lockDueMonth} once a day with the
 * business date. On or after {@code hours.monthLockDay} (1 or 16, S5) the previous month locks: one {@code hours_month_lock} row (its month is UNIQUE, so the month
 * locks once) and one {@code cbs_manhour_snapshot} row per CBS and activity, the Approved line hours dated in that month, in one transaction. A snapshot is written once
 * and never updated, so hours approved later for a closed month change no snapshot (the figure stays what the lock froze). Lines whose time sheet has no CBS (D40)
 * have no snapshot row. Only the previous month is locked, so a gap (the job not running for more than a month) is not caught up (ASSUMPTION). Refusing hours dated in a
 * locked month is BPA-67 (spec S5).
 */
@Service
public class MonthLockService {

    private final JdbcTemplate jdbc;
    private final AlertSettings settings;
    private final Clock clock;

    public MonthLockService(JdbcTemplate jdbc, AlertSettings settings, Clock clock) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.clock = clock;
    }

    /** Locks the previous month when the business date has reached the lock day; returns that month's first day, or empty when none is due or it is already locked. */
    @Transactional
    public Optional<LocalDate> lockDueMonth(LocalDate businessDate, String username) {
        int lockDay = settings.monthLockDay();
        if (businessDate.getDayOfMonth() < lockDay) {
            return Optional.empty();
        }
        LocalDate month = businessDate.withDayOfMonth(1).minusMonths(1);
        Timestamp at = Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS));
        List<UUID> locked = jdbc.queryForList("""
                INSERT INTO bpa.hours_month_lock (month, locked_at, lock_day, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (month) DO NOTHING RETURNING id""", UUID.class, Date.valueOf(month), at, lockDay, at, username, at, username);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        jdbc.update("""
                INSERT INTO bpa.cbs_manhour_snapshot (month, cbs_code_ref, activity_ref, approved_manhours, frozen_at, created_at, created_by, updated_at, updated_by)
                SELECT ?, t.cbs_code_ref, t.activity_ref, sum(l.hours), ?, ?, ?, ?, ?
                FROM bpa.digital_timesheet_line l JOIN bpa.digital_timesheet t ON t.id = l.digital_timesheet_id
                WHERE l.status = 'Approved' AND l.work_date >= ? AND l.work_date < ? AND t.cbs_code_ref IS NOT NULL
                GROUP BY t.cbs_code_ref, t.activity_ref""", Date.valueOf(month), at, at, username, at, username, Date.valueOf(month), Date.valueOf(month.plusMonths(1)));
        return Optional.of(month);
    }
}
