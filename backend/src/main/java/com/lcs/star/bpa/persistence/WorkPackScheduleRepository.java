package com.lcs.star.bpa.persistence;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Work Pack's schedule (BPA-11, data model §4.4): one row per Work Pack, so the dates are fixed once saved (D27) and a retried
 * job never writes a second one (D65, S7). Audit rows per business column (D64, §6.1).
 */
@Repository
public class WorkPackScheduleRepository {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public WorkPackScheduleRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** True when the Work Pack already has its schedule (so a retried job changes nothing). */
    public boolean exists(UUID workpackId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM bpa.workpack_schedule WHERE workpack_id = ?)", Boolean.class, workpackId));
    }

    /**
     * Saves the schedule once: {@code workpack_id} is UNIQUE and the insert is ON CONFLICT DO NOTHING, so of two racing runs only
     * one writes (and audits). Returns false when the schedule was already there.
     */
    @Transactional
    public boolean save(UUID workpackId, LocalDate plannedStart, LocalDate plannedEnd, String username) {
        Instant now = clock.instant();
        Timestamp at = Timestamp.from(now);
        List<UUID> inserted = jdbc.queryForList("""
                INSERT INTO bpa.workpack_schedule (workpack_id, planned_start_date, planned_end_date, scheduled_by,
                                                   created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (workpack_id) DO NOTHING
                RETURNING id""", UUID.class, workpackId, plannedStart, plannedEnd, username, at, username, at, username);
        if (inserted.isEmpty()) {
            return false;
        }
        UUID id = inserted.get(0);
        audit(id, "workpack_id", workpackId.toString(), username, at);
        audit(id, "planned_start_date", plannedStart.toString(), username, at);
        audit(id, "planned_end_date", plannedEnd.toString(), username, at);
        audit(id, "scheduled_by", username, username, at);
        return true;
    }

    private void audit(UUID recordId, String column, String newValue, String username, Timestamp at) {
        jdbc.update("""
                INSERT INTO bpa.audit_log (table_name, record_id, column_name, old_value, new_value, username, changed_at)
                VALUES ('workpack_schedule', ?, ?, NULL, ?, ?, ?)""", recordId, column, newValue, username, at);
    }
}
