package com.lcs.star.bpa.persistence;

import com.lcs.star.bpa.api.generated.model.HourJournalLine;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Project Hour Journal Lines (BPA-14; data model 5.6): created by {@code create-hour-journal-lines} and read by
 * {@code GET /api/v1/hour-journal-lines}. A Line is one assigned Worker under the Work Pack's Header (BPA-7), with the Worker's role and
 * designation copied from the Worker master when the Line is created and never refreshed (N3, C14) and the assignment's estimated hours.
 * Every new Line writes {@code audit_log} rows in the same transaction (D64, 6.1).
 */
@Repository
public class HourJournalLineRepository {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public HourJournalLineRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** What the worker needs back: the Lines it has just created (none on a re-run), or that the Work Pack has no Header yet. */
    public record Created(boolean hasHeader, int created) {
    }

    /**
     * One Line per Worker assigned to the Work Pack (data model 5.3), under its Header. The key is unique(header_id, worker_id), so a
     * re-run inserts nothing new and writes no audit row (D65, 09 S7). Rejected rows never reach worker_assignment (BPA-22), so they get no
     * Line. The user on the Line and in the audit log is the assignment's: the person who uploaded the file (D63, BPA-22).
     */
    @Transactional
    public Created createFor(UUID workpackId) {
        if (jdbc.queryForObject("SELECT count(*) FROM bpa.hour_journal_header WHERE workpack_id = ?", Integer.class, workpackId) == 0) {
            return new Created(false, 0);
        }
        Instant at = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Timestamp now = Timestamp.from(at);
        List<NewLine> created = jdbc.query("""
                INSERT INTO bpa.hour_journal_line (header_id, worker_id, role, designation, estimated_hours, status,
                                                   created_at, created_by, updated_at, updated_by)
                SELECT h.id, a.worker_id, w.role, w.designation, a.estimated_hours, 'Open', ?, a.created_by, ?, a.created_by
                FROM bpa.worker_assignment a
                JOIN bpa.worker w ON w.id = a.worker_id
                JOIN bpa.hour_journal_header h ON h.workpack_id = a.workpack_id
                WHERE a.workpack_id = ?
                ORDER BY a.worker_id
                ON CONFLICT (header_id, worker_id) DO NOTHING
                RETURNING id, header_id, worker_id, role, designation, estimated_hours, status, created_by""",
                (rs, n) -> new NewLine(rs.getObject("id", UUID.class), rs.getObject("header_id", UUID.class),
                        rs.getObject("worker_id", UUID.class), rs.getString("role"), rs.getString("designation"),
                        rs.getBigDecimal("estimated_hours"), rs.getString("status"), rs.getString("created_by")),
                now, now, workpackId);
        for (NewLine line : created) {
            audit(line.id(), "header_id", line.headerId().toString(), line.createdBy(), now);
            audit(line.id(), "worker_id", line.workerId().toString(), line.createdBy(), now);
            audit(line.id(), "role", line.role(), line.createdBy(), now);
            audit(line.id(), "designation", line.designation(), line.createdBy(), now);
            audit(line.id(), "estimated_hours", line.estimatedHours().stripTrailingZeros().toPlainString(), line.createdBy(), now);
            audit(line.id(), "status", line.status(), line.createdBy(), now);
        }
        return new Created(true, created.size());
    }

    /** The Lines of the Work Pack with this number, by Badge No; empty when it has none yet; no value when there is no such Work Pack. */
    public Optional<List<HourJournalLine>> findByWorkpackNumber(String workpackNumber) {
        List<UUID> workpack = jdbc.queryForList("SELECT id FROM bpa.workpack WHERE workpack_number = ?", UUID.class, workpackNumber);
        if (workpack.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(jdbc.query("""
                SELECT w.employee_ref, l.role, l.designation, l.estimated_hours, l.status
                FROM bpa.hour_journal_line l
                JOIN bpa.hour_journal_header h ON h.id = l.header_id
                JOIN bpa.worker w ON w.id = l.worker_id
                WHERE h.workpack_id = ? ORDER BY w.employee_ref""",
                (rs, n) -> new HourJournalLine(rs.getString("employee_ref"), rs.getString("role"), rs.getString("designation"),
                        rs.getBigDecimal("estimated_hours"), HourJournalLine.StatusEnum.fromValue(rs.getString("status"))),
                workpack.get(0)));
    }

    private record NewLine(UUID id, UUID headerId, UUID workerId, String role, String designation, BigDecimal estimatedHours, String status,
            String createdBy) {
    }

    private void audit(UUID recordId, String column, String newValue, String username, Timestamp at) {
        jdbc.update("""
                INSERT INTO bpa.audit_log (table_name, record_id, column_name, old_value, new_value, username, changed_at)
                VALUES ('hour_journal_line', ?, ?, NULL, ?, ?, ?)""", recordId, column, newValue, username, at);
    }
}
