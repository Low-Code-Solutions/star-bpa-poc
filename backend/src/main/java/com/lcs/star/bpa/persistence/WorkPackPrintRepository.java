package com.lcs.star.bpa.persistence;

import com.lcs.star.bpa.print.PrintData;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Workpack print (BPA-10, data model §4.5): one row per Work Pack (D29), its content read from the Work Pack, its phases,
 * materials and schedule, and the cutting sheets linked to its drawing (D30). Storing the print opens the Work Pack for assignment
 * files: assignment_state becomes AwaitingFile (D50, D61, G7). Audit rows per business column (D64, §6.1).
 */
@Repository
public class WorkPackPrintRepository {

    private final JdbcTemplate jdbc;

    public WorkPackPrintRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** True when the Work Pack already has its print (so a retried job changes nothing). */
    public boolean exists(UUID workpackId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM bpa.workpack_print WHERE workpack_id = ?)", Boolean.class, workpackId));
    }

    /** What the print shows, or empty when the Work Pack has no schedule yet (the print comes after scheduling, D4). */
    public Optional<PrintData> loadData(UUID workpackId) {
        var head = jdbc.query("""
                SELECT w.workpack_number, w.activity_ref, w.drawing_ref, w.department_ref, w.completion_date,
                       s.planned_start_date, s.planned_end_date
                FROM bpa.workpack w JOIN bpa.workpack_schedule s ON s.workpack_id = w.id
                WHERE w.id = ?""",
                (rs, n) -> new Object[] {rs.getString("workpack_number"), rs.getString("activity_ref"), rs.getString("drawing_ref"),
                        rs.getString("department_ref"), rs.getObject("completion_date", LocalDate.class),
                        rs.getObject("planned_start_date", LocalDate.class), rs.getObject("planned_end_date", LocalDate.class)},
                workpackId).stream().findFirst();
        if (head.isEmpty()) {
            return Optional.empty();
        }
        Object[] h = head.get();
        // The phases in the FRD order (Engineering, Cutting, Welding, Finishing); a material line in the order it was saved.
        List<PrintData.PhaseLine> phases = jdbc.query("""
                SELECT phase, manhours FROM bpa.workpack_phase WHERE workpack_id = ?
                ORDER BY array_position(ARRAY['Engineering', 'Cutting', 'Welding', 'Finishing'], phase), phase""",
                (rs, n) -> new PrintData.PhaseLine(rs.getString("phase"), rs.getBigDecimal("manhours")), workpackId);
        List<PrintData.MaterialLine> materials = jdbc.query(
                "SELECT material_ref, phase FROM bpa.workpack_material WHERE workpack_id = ? ORDER BY created_at, id",
                (rs, n) -> new PrintData.MaterialLine(rs.getString("material_ref"), rs.getString("phase")), workpackId);
        List<String> cuttingSheets = jdbc.queryForList(
                "SELECT cutting_sheet_ref FROM bpa.cutting_sheet WHERE drawing_ref = ? ORDER BY cutting_sheet_ref", String.class, h[2]);
        return Optional.of(new PrintData((String) h[0], (String) h[1], (String) h[2], (String) h[3], (LocalDate) h[4], (LocalDate) h[5],
                (LocalDate) h[6], phases, materials, cuttingSheets));
    }

    /**
     * Stores the print once: {@code workpack_id} is UNIQUE and the insert is ON CONFLICT DO NOTHING, so of two racing runs only one
     * writes. Then, only if the state is still empty, assignment_state becomes AwaitingFile. Returns false when the print was already
     * there.
     */
    @Transactional
    public boolean save(UUID workpackId, byte[] pdf, String sha256, Instant generatedAt, String username) {
        Timestamp at = Timestamp.from(generatedAt);
        List<UUID> inserted = jdbc.queryForList("""
                INSERT INTO bpa.workpack_print (workpack_id, file, sha256, generated_at, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (workpack_id) DO NOTHING
                RETURNING id""", UUID.class, workpackId, pdf, sha256, at, at, username, at, username);
        if (inserted.isEmpty()) {
            return false;
        }
        UUID id = inserted.get(0);
        audit("workpack_print", id, "workpack_id", null, workpackId.toString(), username, at);
        // The file is logged as its hash, not its bytes (data model §6.1, A3).
        audit("workpack_print", id, "file", null, "sha256:" + sha256, username, at);
        audit("workpack_print", id, "sha256", null, sha256, username, at);
        audit("workpack_print", id, "generated_at", null, generatedAt.toString(), username, at);
        int opened = jdbc.update("""
                UPDATE bpa.workpack SET assignment_state = 'AwaitingFile', updated_at = ?, updated_by = ?
                WHERE id = ? AND assignment_state IS NULL""", at, username, workpackId);
        if (opened == 1) {
            audit("workpack", workpackId, "assignment_state", null, "AwaitingFile", username, at);
        }
        return true;
    }

    /** Whether a Work Pack with this number exists. */
    public boolean workpackExists(String workpackNumber) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM bpa.workpack WHERE workpack_number = ?)", Boolean.class, workpackNumber));
    }

    /** The stored PDF of the Work Pack with this number, or empty when it has none (yet). */
    public Optional<byte[]> findFile(String workpackNumber) {
        return jdbc.query("""
                SELECT p.file FROM bpa.workpack_print p JOIN bpa.workpack w ON w.id = p.workpack_id WHERE w.workpack_number = ?""",
                (rs, n) -> rs.getBytes("file"), workpackNumber).stream().findFirst();
    }

    /** The Work Packs that have a print, newest first (the portal's list). */
    public List<Summary> list() {
        return jdbc.query("""
                SELECT w.workpack_number, w.drawing_ref, s.planned_start_date, s.planned_end_date, p.generated_at
                FROM bpa.workpack_print p
                JOIN bpa.workpack w ON w.id = p.workpack_id
                JOIN bpa.workpack_schedule s ON s.workpack_id = w.id
                ORDER BY p.generated_at DESC, w.workpack_number DESC""",
                (rs, n) -> new Summary(rs.getString("workpack_number"), rs.getString("drawing_ref"),
                        rs.getObject("planned_start_date", LocalDate.class), rs.getObject("planned_end_date", LocalDate.class),
                        rs.getTimestamp("generated_at").toInstant()));
    }

    public record Summary(String workpackNumber, String drawingRef, LocalDate plannedStartDate, LocalDate plannedEndDate,
            Instant generatedAt) {
    }

    private void audit(String table, UUID recordId, String column, String oldValue, String newValue, String username, Timestamp at) {
        jdbc.update("""
                INSERT INTO bpa.audit_log (table_name, record_id, column_name, old_value, new_value, username, changed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""", table, recordId, column, oldValue, newValue, username, at);
    }
}
