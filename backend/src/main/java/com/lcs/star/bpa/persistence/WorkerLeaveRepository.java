package com.lcs.star.bpa.persistence;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * worker_leave (BPA-75; E2-22; data model section 13, A12), read only: approved leave of a Worker, a seeded copy of myPeople (ASSUMPTION). The one lookup of
 * "is this Worker on approved leave on this date", shared by the save-time check ({@code HourLineService}) and the send-time check
 * ({@code PrepareHoursSubmissionWorker}), so there is no second copy of the rule. Only approved leave is stored (no status column, Q-DM23), so a row is
 * leave; any approved leave on a date blocks the whole day (decision 12 Nov 2026, half days are out of scope).
 */
@Repository
public class WorkerLeaveRepository {

    private final JdbcTemplate jdbc;

    public WorkerLeaveRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** True when a row of this Worker has {@code leave_from <= workDate <= leave_to} (both ends inclusive). Overlapping rows are harmless: it is an EXISTS. */
    public boolean onLeave(UUID workerId, LocalDate workDate) {
        if (workerId == null || workDate == null) {
            return false;
        }
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM bpa.worker_leave WHERE worker_id = ? AND leave_from <= ? AND leave_to >= ?)", Boolean.class, workerId, workDate, workDate));
    }
}
