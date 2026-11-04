package com.lcs.star.bpa.persistence;

import com.lcs.star.bpa.domain.WorkPackInput;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saves a new Work Pack with its phases and materials (BPA-6; data model §4.1-4.3, §6.1). Idempotent on the Camunda process
 * instance key (D65, 09 S7): a retried save-workpack job gets the Work Pack the first run saved, unchanged. The number comes
 * from {@code bpa.workpack_number_seq} (D15, A2; a rolled-back save leaves a gap, which D15 allows). One {@code audit_log}
 * row per business column, in the same transaction (D64; old value null on insert; canonical value format §6.1).
 */
@Repository
public class WorkPackRepository {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public WorkPackRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public SavedWorkPack save(long processInstanceKey, WorkPackInput input, String username) {
        Optional<SavedWorkPack> existing = find(processInstanceKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        String number = jdbc.queryForObject("SELECT 'WP-' || lpad(nextval('bpa.workpack_number_seq')::text, 6, '0')", String.class);
        // ON CONFLICT: two runs of the same job racing past find() leave exactly one row; the loser reads the winner's.
        List<UUID> inserted = jdbc.queryForList("""
                INSERT INTO bpa.workpack (workpack_number, activity_ref, drawing_ref, department_ref, completion_date, status,
                                          process_instance_key, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'Draft', ?, ?, ?)
                ON CONFLICT (process_instance_key) DO NOTHING
                RETURNING id""", UUID.class, number, input.activityRef(), input.drawingRef(), input.departmentRef(),
                input.completionDate(), processInstanceKey, username, username);
        if (inserted.isEmpty()) {
            return find(processInstanceKey).orElseThrow();
        }
        UUID workpackId = inserted.get(0);
        Instant now = clock.instant();
        audit("workpack", workpackId, "workpack_number", number, username, now);
        audit("workpack", workpackId, "activity_ref", input.activityRef(), username, now);
        audit("workpack", workpackId, "drawing_ref", input.drawingRef(), username, now);
        audit("workpack", workpackId, "department_ref", input.departmentRef(), username, now);
        audit("workpack", workpackId, "completion_date", text(input.completionDate()), username, now);
        audit("workpack", workpackId, "status", "Draft", username, now);
        audit("workpack", workpackId, "process_instance_key", Long.toString(processInstanceKey), username, now);

        for (WorkPackInput.Phase phase : input.phases()) {
            UUID id = jdbc.queryForObject("""
                    INSERT INTO bpa.workpack_phase (workpack_id, phase, manhours, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?) RETURNING id""", UUID.class, workpackId, phase.phase(), phase.manhours(), username, username);
            audit("workpack_phase", id, "workpack_id", workpackId.toString(), username, now);
            audit("workpack_phase", id, "phase", phase.phase(), username, now);
            audit("workpack_phase", id, "manhours", text(phase.manhours()), username, now);
        }
        for (WorkPackInput.Material material : input.materials()) {
            UUID id = jdbc.queryForObject("""
                    INSERT INTO bpa.workpack_material (workpack_id, material_ref, phase, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?) RETURNING id""", UUID.class, workpackId, material.materialRef(), material.phase(),
                    username, username);
            audit("workpack_material", id, "workpack_id", workpackId.toString(), username, now);
            audit("workpack_material", id, "material_ref", material.materialRef(), username, now);
            audit("workpack_material", id, "phase", material.phase(), username, now);
        }
        return new SavedWorkPack(workpackId, number);
    }

    private Optional<SavedWorkPack> find(long processInstanceKey) {
        return jdbc.query("SELECT id, workpack_number FROM bpa.workpack WHERE process_instance_key = ?",
                (rs, n) -> new SavedWorkPack(rs.getObject("id", UUID.class), rs.getString("workpack_number")), processInstanceKey)
                .stream().findFirst();
    }

    private void audit(String table, UUID recordId, String column, String newValue, String username, Instant at) {
        jdbc.update("""
                INSERT INTO bpa.audit_log (table_name, record_id, column_name, old_value, new_value, username, changed_at)
                VALUES (?, ?, ?, NULL, ?, ?, ?)""", table, recordId, column, newValue, username, Timestamp.from(at));
    }

    /** Data model §6.1: numbers without trailing zeros, dates ISO 8601. */
    private static String text(BigDecimal number) {
        return number.stripTrailingZeros().toPlainString();
    }

    private static String text(LocalDate date) {
        return date.toString();
    }

    public record SavedWorkPack(UUID id, String workpackNumber) {
    }
}
