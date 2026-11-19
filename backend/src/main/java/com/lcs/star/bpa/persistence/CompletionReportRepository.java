package com.lcs.star.bpa.persistence;

import com.lcs.star.bpa.api.generated.model.CompletionReportResult;
import com.lcs.star.bpa.api.generated.model.CompletionReportRow;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * completion_report and completion_report_row (BPA-59; data model §13.3.3): the intake log of an uploaded FMS completion report, every row as written
 * (raw text) with its outcome, and the report's own outcome (NULL until the Read completion report step decides: Accepted or Invalid, ASSUMPTION values).
 * As the time sheet upload (BPA-52) the intake rows are not audited one by one; the report's outcome change is (D64).
 */
@Repository
public class CompletionReportRepository {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public CompletionReportRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** One Read row: the Work Pack and the completion % as written. */
    public record ReadRow(String workpackNumber, String completionRaw) {
    }

    public UUID insertReport(UUID periodId, String fileName, String sha256, String username, String outcome) {
        Timestamp at = now();
        return jdbc.queryForObject("""
                INSERT INTO bpa.completion_report (progress_period_id, file_name, sha256, uploaded_by, received_at, outcome, created_at, created_by, updated_at,
                                                   updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", UUID.class, periodId, fileName, sha256, username, at, outcome, at, username, at, username);
    }

    /** One logged row. {@code source} is FMS or PMS or null (plan Q1); {@code sourceRaw} is the cell as written. */
    public void insertRow(UUID reportId, int rowNumber, String workpackNumber, String completionRaw, String hoursRaw, String sourceRaw, String source,
            String outcome, String username) {
        Timestamp at = now();
        jdbc.update("""
                INSERT INTO bpa.completion_report_row (completion_report_id, row_number, workpack_number, completion_pct_raw, actual_hours_raw, source_raw, source,
                                                       outcome, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", reportId, rowNumber, workpackNumber, completionRaw, hoursRaw, sourceRaw, source, outcome, at, username,
                at, username);
    }

    /** The Work Pack's status, or empty when there is no such Work Pack. */
    public Optional<String> workPackStatus(String workpackNumber) {
        return jdbc.queryForList("SELECT status FROM bpa.workpack WHERE workpack_number = ?", String.class, workpackNumber).stream().findFirst();
    }

    /** The progress period the report was uploaded into, or empty when there is no such report. */
    public Optional<UUID> periodOf(UUID reportId) {
        return jdbc.queryForList("SELECT progress_period_id FROM bpa.completion_report WHERE id = ?", UUID.class, reportId).stream().findFirst();
    }

    /** The report's own outcome (Accepted, Invalid), or null while undecided. {@code FOR UPDATE}: a re-run and a first run do not decide it twice. */
    public Optional<String> lockedOutcome(UUID reportId) {
        return jdbc.query("SELECT outcome FROM bpa.completion_report WHERE id = ? FOR UPDATE", (rs, n) -> Optional.ofNullable(rs.getString("outcome")), reportId)
                .stream().findFirst().orElse(Optional.empty());
    }

    public void setOutcome(UUID reportId, String outcome, String username) {
        Timestamp at = now();
        jdbc.update("UPDATE bpa.completion_report SET outcome = ?, updated_at = ?, updated_by = ? WHERE id = ?", outcome, at, username, reportId);
        jdbc.update("""
                INSERT INTO bpa.audit_log (table_name, record_id, column_name, old_value, new_value, username, changed_at)
                VALUES ('completion_report', ?, 'outcome', NULL, ?, ?, ?)""", reportId, outcome, username, at);
    }

    /** The rows that were Read, in file order. */
    public List<ReadRow> readRows(UUID reportId) {
        return jdbc.query("""
                SELECT workpack_number, completion_pct_raw FROM bpa.completion_report_row
                WHERE completion_report_id = ? AND outcome = 'Read' ORDER BY row_number, id""", (rs, n) -> new ReadRow(rs.getString("workpack_number"),
                rs.getString("completion_pct_raw")), reportId);
    }

    /** The report and its rows, for the upload's answer and for {@code GET .../result}. */
    public Optional<CompletionReportResult> result(UUID reportId) {
        var header = jdbc.query("""
                SELECT r.file_name, r.received_at, r.outcome, p.week_ending FROM bpa.completion_report r JOIN bpa.progress_period p ON p.id = r.progress_period_id
                WHERE r.id = ?""", (rs, n) -> new Object[] {rs.getString("file_name"), rs.getTimestamp("received_at").toInstant(), rs.getString("outcome"),
                rs.getDate("week_ending").toLocalDate()}, reportId).stream().findFirst();
        if (header.isEmpty()) {
            return Optional.empty();
        }
        List<CompletionReportRow> rows = jdbc.query("""
                SELECT row_number, workpack_number, completion_pct_raw, actual_hours_raw, source_raw, outcome FROM bpa.completion_report_row
                WHERE completion_report_id = ? ORDER BY row_number, id""", (rs, n) -> {
                    CompletionReportRow row = new CompletionReportRow();
                    row.setRowNumber(rs.getInt("row_number"));
                    row.setWorkpackNumber(rs.getString("workpack_number"));
                    row.setCompletionPct(rs.getString("completion_pct_raw"));
                    row.setActualHours(rs.getString("actual_hours_raw"));
                    row.setSource(rs.getString("source_raw"));
                    row.setOutcome(rs.getString("outcome"));
                    return row;
                }, reportId);
        Object[] h = header.get();
        CompletionReportResult result = new CompletionReportResult(reportId, (String) h[0], ((Instant) h[1]).atOffset(ZoneOffset.UTC),
                (java.time.LocalDate) h[3], rows);
        result.setOutcome((String) h[2]);
        return Optional.of(result);
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS));
    }
}
