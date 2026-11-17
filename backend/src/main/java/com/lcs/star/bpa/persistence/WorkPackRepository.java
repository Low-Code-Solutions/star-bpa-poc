package com.lcs.star.bpa.persistence;

import com.lcs.star.bpa.domain.WorkPackInput;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
        // S9: one clock for the audit columns and audit_log.changed_at, not the database's now().
        Instant now = clock.instant();
        Timestamp at = Timestamp.from(now);
        String number = jdbc.queryForObject("SELECT 'WP-' || lpad(nextval('bpa.workpack_number_seq')::text, 6, '0')", String.class);
        // ON CONFLICT: two runs of the same job racing past find() leave exactly one row; the loser reads the winner's.
        List<UUID> inserted = jdbc.queryForList("""
                INSERT INTO bpa.workpack (workpack_number, activity_ref, drawing_ref, department_ref, completion_date, status,
                                          process_instance_key, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, 'Draft', ?, ?, ?, ?, ?)
                ON CONFLICT (process_instance_key) DO NOTHING
                RETURNING id""", UUID.class, number, input.activityRef(), input.drawingRef(), input.departmentRef(),
                input.completionDate(), processInstanceKey, at, username, at, username);
        if (inserted.isEmpty()) {
            return find(processInstanceKey).orElseThrow();
        }
        UUID workpackId = inserted.get(0);
        audit("workpack", workpackId, "workpack_number", number, username, now);
        audit("workpack", workpackId, "activity_ref", input.activityRef(), username, now);
        audit("workpack", workpackId, "drawing_ref", input.drawingRef(), username, now);
        audit("workpack", workpackId, "department_ref", input.departmentRef(), username, now);
        audit("workpack", workpackId, "completion_date", text(input.completionDate()), username, now);
        audit("workpack", workpackId, "status", "Draft", username, now);
        audit("workpack", workpackId, "process_instance_key", Long.toString(processInstanceKey), username, now);

        for (WorkPackInput.Phase phase : input.phases()) {
            UUID id = jdbc.queryForObject("""
                    INSERT INTO bpa.workpack_phase (workpack_id, phase, manhours, created_at, created_by, updated_at, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id""", UUID.class, workpackId, phase.phase(), phase.manhours(),
                    at, username, at, username);
            audit("workpack_phase", id, "workpack_id", workpackId.toString(), username, now);
            audit("workpack_phase", id, "phase", phase.phase(), username, now);
            audit("workpack_phase", id, "manhours", text(phase.manhours()), username, now);
        }
        for (WorkPackInput.Material material : input.materials()) {
            UUID id = jdbc.queryForObject("""
                    INSERT INTO bpa.workpack_material (workpack_id, material_ref, phase, created_at, created_by, updated_at, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id""", UUID.class, workpackId, material.materialRef(), material.phase(),
                    at, username, at, username);
            audit("workpack_material", id, "workpack_id", workpackId.toString(), username, now);
            audit("workpack_material", id, "material_ref", material.materialRef(), username, now);
            audit("workpack_material", id, "phase", material.phase(), username, now);
        }
        return new SavedWorkPack(workpackId, number);
    }

    /** The Work Pack saved for this process instance, if any (the D65 re-run key). */
    public Optional<SavedWorkPack> find(long processInstanceKey) {
        return jdbc.query("SELECT id, workpack_number FROM bpa.workpack WHERE process_instance_key = ?",
                (rs, n) -> new SavedWorkPack(rs.getObject("id", UUID.class), rs.getString("workpack_number")), processInstanceKey)
                .stream().findFirst();
    }

    /** The Work Pack with this number, if any (BPA-49: the one-off timesheet start names it by number). */
    public Optional<SavedWorkPack> findByNumber(String workpackNumber) {
        return jdbc.query("SELECT id, workpack_number FROM bpa.workpack WHERE workpack_number = ?",
                (rs, n) -> new SavedWorkPack(rs.getObject("id", UUID.class), rs.getString("workpack_number")), workpackNumber)
                .stream().findFirst();
    }

    /** The Work Pack's status (Draft, Released, ...), or empty if there is no such Work Pack. */
    public Optional<String> status(UUID workpackId) {
        return jdbc.queryForList("SELECT status FROM bpa.workpack WHERE id = ?", String.class, workpackId).stream().findFirst();
    }

    /** The ids of the Work Pack's material lines (BPA-23: a correction may name only these). */
    public Set<String> materialLineIds(UUID workpackId) {
        return new HashSet<>(jdbc.queryForList("SELECT id::text FROM bpa.workpack_material WHERE workpack_id = ?", String.class, workpackId));
    }

    /**
     * The saved phases and material lines, as the save step hands them back to the process (BPA-23): the Confirm task's read
     * allow-list returns them, so the screen can round-trip each material line's id (data model A4).
     */
    public Map<String, Object> lines(UUID workpackId) {
        List<Map<String, Object>> phases = jdbc.query(
                "SELECT phase, manhours FROM bpa.workpack_phase WHERE workpack_id = ? ORDER BY array_position(ARRAY['Engineering','Cutting','Welding','Finishing'], phase)",
                (rs, n) -> {
                    Map<String, Object> line = new LinkedHashMap<>();
                    line.put("phase", rs.getString("phase"));
                    // plain form, so 150.00 is sent as 150, never 1.5E+2
                    line.put("manhours", new BigDecimal(rs.getBigDecimal("manhours").stripTrailingZeros().toPlainString()));
                    return line;
                }, workpackId);
        List<Map<String, Object>> materials = jdbc.query(
                "SELECT id, material_ref, phase FROM bpa.workpack_material WHERE workpack_id = ? ORDER BY created_at, id",
                (rs, n) -> {
                    Map<String, Object> line = new LinkedHashMap<>();
                    line.put("id", rs.getObject("id", UUID.class).toString());
                    line.put("materialRef", rs.getString("material_ref"));
                    line.put("phase", rs.getString("phase"));
                    return line;
                }, workpackId);
        return Map.of("phases", phases, "materials", materials);
    }

    /**
     * Applies a Confirm Workpack correction to a Draft Work Pack in place (BPA-23; data model F13, A4), in one transaction:
     * changed workpack columns are updated; phases are matched by name (update, insert, delete); material lines are matched by
     * id, an id-less line by an unclaimed line with the same material and phase, else inserted; unclaimed lines are deleted.
     * The material FK and UNIQUE are DEFERRABLE, so statement order does not matter for lines (phases are matched by name, so
     * their non-deferrable UNIQUE is never hit). One audit row per changed business column, old -> new
     * (D64, §6.1). Applying the same correction again changes nothing, so a retried job is harmless (D65, S7).
     */
    @Transactional
    public void correct(UUID workpackId, WorkPackInput input, String username) {
        Instant now = clock.instant();
        Timestamp at = Timestamp.from(now);

        Map<String, Object> current = jdbc.queryForMap(
                "SELECT activity_ref, drawing_ref, department_ref, completion_date FROM bpa.workpack WHERE id = ? FOR UPDATE", workpackId);
        updateIfChanged(workpackId, "activity_ref", (String) current.get("activity_ref"), input.activityRef(), username, at, now);
        updateIfChanged(workpackId, "drawing_ref", (String) current.get("drawing_ref"), input.drawingRef(), username, at, now);
        updateIfChanged(workpackId, "department_ref", (String) current.get("department_ref"), input.departmentRef(), username, at, now);
        LocalDate date = ((java.sql.Date) current.get("completion_date")).toLocalDate();
        if (!date.equals(input.completionDate())) {
            jdbc.update("UPDATE bpa.workpack SET completion_date = ?, updated_at = ?, updated_by = ? WHERE id = ?",
                    input.completionDate(), at, username, workpackId);
            audit("workpack", workpackId, "completion_date", text(date), text(input.completionDate()), username, now);
        }

        // Phases, by name (UNIQUE (workpack_id, phase)).
        Map<String, Map<String, Object>> phases = new LinkedHashMap<>();
        jdbc.queryForList("SELECT id, phase, manhours FROM bpa.workpack_phase WHERE workpack_id = ?", workpackId)
                .forEach(row -> phases.put((String) row.get("phase"), row));
        Set<String> wanted = new HashSet<>();
        for (WorkPackInput.Phase phase : input.phases()) {
            wanted.add(phase.phase());
            Map<String, Object> existing = phases.get(phase.phase());
            if (existing == null) {
                UUID id = jdbc.queryForObject("""
                        INSERT INTO bpa.workpack_phase (workpack_id, phase, manhours, created_at, created_by, updated_at, updated_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id""", UUID.class, workpackId, phase.phase(), phase.manhours(),
                        at, username, at, username);
                audit("workpack_phase", id, "workpack_id", null, workpackId.toString(), username, now);
                audit("workpack_phase", id, "phase", null, phase.phase(), username, now);
                audit("workpack_phase", id, "manhours", null, text(phase.manhours()), username, now);
            } else {
                BigDecimal before = (BigDecimal) existing.get("manhours");
                if (before.compareTo(phase.manhours()) != 0) {
                    UUID id = (UUID) existing.get("id");
                    jdbc.update("UPDATE bpa.workpack_phase SET manhours = ?, updated_at = ?, updated_by = ? WHERE id = ?",
                            phase.manhours(), at, username, id);
                    audit("workpack_phase", id, "manhours", text(before), text(phase.manhours()), username, now);
                }
            }
        }
        for (Map.Entry<String, Map<String, Object>> existing : phases.entrySet()) {
            if (!wanted.contains(existing.getKey())) {
                UUID id = (UUID) existing.getValue().get("id");
                jdbc.update("DELETE FROM bpa.workpack_phase WHERE id = ?", id);
                audit("workpack_phase", id, "workpack_id", workpackId.toString(), null, username, now);
                audit("workpack_phase", id, "phase", existing.getKey(), null, username, now);
                audit("workpack_phase", id, "manhours", text((BigDecimal) existing.getValue().get("manhours")), null, username, now);
            }
        }

        // Material lines, by id; an id-less line takes an unclaimed line with the same material and phase, else is new.
        Map<String, Map<String, Object>> lines = new LinkedHashMap<>();
        jdbc.queryForList("SELECT id::text AS id, material_ref, phase FROM bpa.workpack_material WHERE workpack_id = ?", workpackId)
                .forEach(row -> lines.put((String) row.get("id"), row));
        // Two passes (BPA-23 review): lines with an id claim their rows first, so an id-less line listed earlier can never take a
        // row that an id line also updates. Duplicate ids are refused by the worker before this runs.
        Set<String> claimed = new HashSet<>();
        for (WorkPackInput.Material material : input.materials()) {
            if (material.id() != null && lines.containsKey(material.id())) {
                claimed.add(material.id());
            }
        }
        for (WorkPackInput.Material material : input.materials()) {
            if (material.id() != null && lines.containsKey(material.id())) {
                Map<String, Object> existing = lines.get(material.id());
                UUID id = UUID.fromString(material.id());
                if (!material.materialRef().equals(existing.get("material_ref"))) {
                    jdbc.update("UPDATE bpa.workpack_material SET material_ref = ?, updated_at = ?, updated_by = ? WHERE id = ?",
                            material.materialRef(), at, username, id);
                    audit("workpack_material", id, "material_ref", (String) existing.get("material_ref"), material.materialRef(), username, now);
                }
                if (!material.phase().equals(existing.get("phase"))) {
                    jdbc.update("UPDATE bpa.workpack_material SET phase = ?, updated_at = ?, updated_by = ? WHERE id = ?",
                            material.phase(), at, username, id);
                    audit("workpack_material", id, "phase", (String) existing.get("phase"), material.phase(), username, now);
                }
                continue;
            }
            String same = lines.entrySet().stream()
                    .filter(e -> !claimed.contains(e.getKey()) && material.materialRef().equals(e.getValue().get("material_ref"))
                            && material.phase().equals(e.getValue().get("phase")))
                    .map(Map.Entry::getKey).findFirst().orElse(null);
            if (same != null) {
                claimed.add(same);
                continue;
            }
            UUID id = jdbc.queryForObject("""
                    INSERT INTO bpa.workpack_material (workpack_id, material_ref, phase, created_at, created_by, updated_at, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id""", UUID.class, workpackId, material.materialRef(), material.phase(),
                    at, username, at, username);
            audit("workpack_material", id, "workpack_id", null, workpackId.toString(), username, now);
            audit("workpack_material", id, "material_ref", null, material.materialRef(), username, now);
            audit("workpack_material", id, "phase", null, material.phase(), username, now);
        }
        for (Map.Entry<String, Map<String, Object>> existing : lines.entrySet()) {
            if (!claimed.contains(existing.getKey())) {
                UUID id = UUID.fromString(existing.getKey());
                jdbc.update("DELETE FROM bpa.workpack_material WHERE id = ?", id);
                audit("workpack_material", id, "workpack_id", workpackId.toString(), null, username, now);
                audit("workpack_material", id, "material_ref", (String) existing.getValue().get("material_ref"), null, username, now);
                audit("workpack_material", id, "phase", (String) existing.getValue().get("phase"), null, username, now);
            }
        }
    }

    /**
     * True when the correction asks for exactly what is saved: the same workpack columns, the same phases and man hours, and
     * the same material lines (each id naming a saved line with the same values, the rest matching by material and phase).
     * The worker then completes without re-checking: a retried job whose first run committed, or a confirm of unchanged data
     * (Ahsan, 5 Nov 2026; BPA-23 review).
     */
    public boolean alreadySaved(UUID workpackId, WorkPackInput input) {
        Map<String, Object> current = jdbc.queryForMap(
                "SELECT activity_ref, drawing_ref, department_ref, completion_date FROM bpa.workpack WHERE id = ?", workpackId);
        if (!java.util.Objects.equals(current.get("activity_ref"), input.activityRef())
                || !java.util.Objects.equals(current.get("drawing_ref"), input.drawingRef())
                || !java.util.Objects.equals(current.get("department_ref"), input.departmentRef())
                || !java.util.Objects.equals(((java.sql.Date) current.get("completion_date")).toLocalDate(), input.completionDate())) {
            return false;
        }
        Map<String, BigDecimal> phases = new HashMap<>();
        jdbc.queryForList("SELECT phase, manhours FROM bpa.workpack_phase WHERE workpack_id = ?", workpackId)
                .forEach(row -> phases.put((String) row.get("phase"), (BigDecimal) row.get("manhours")));
        if (phases.size() != input.phases().size()) {
            return false;
        }
        for (WorkPackInput.Phase phase : input.phases()) {
            BigDecimal saved = phases.get(phase.phase());
            if (saved == null || phase.manhours() == null || saved.compareTo(phase.manhours()) != 0) {
                return false;
            }
        }
        Map<String, Map<String, Object>> lines = new HashMap<>();
        jdbc.queryForList("SELECT id::text AS id, material_ref, phase FROM bpa.workpack_material WHERE workpack_id = ?", workpackId)
                .forEach(row -> lines.put((String) row.get("id"), row));
        if (lines.size() != input.materials().size()) {
            return false;
        }
        Set<String> claimed = new HashSet<>();
        for (WorkPackInput.Material material : input.materials()) {
            if (material.id() == null) {
                continue;
            }
            Map<String, Object> saved = lines.get(material.id());
            if (saved == null || !claimed.add(material.id()) || !saved.get("material_ref").equals(material.materialRef())
                    || !saved.get("phase").equals(material.phase())) {
                return false;
            }
        }
        for (WorkPackInput.Material material : input.materials()) {
            if (material.id() != null) {
                continue;
            }
            String same = lines.entrySet().stream()
                    .filter(e -> !claimed.contains(e.getKey()) && e.getValue().get("material_ref").equals(material.materialRef())
                            && e.getValue().get("phase").equals(material.phase()))
                    .map(Map.Entry::getKey).findFirst().orElse(null);
            if (same == null) {
                return false;
            }
            claimed.add(same);
        }
        return true;
    }

    private void updateIfChanged(UUID workpackId, String column, String before, String after, String username, Timestamp at, Instant now) {
        if (!before.equals(after)) {
            // column is one of four fixed names above, never user input
            jdbc.update("UPDATE bpa.workpack SET " + column + " = ?, updated_at = ?, updated_by = ? WHERE id = ?", after, at, username, workpackId);
            audit("workpack", workpackId, column, before, after, username, now);
        }
    }

    private void audit(String table, UUID recordId, String column, String newValue, String username, Instant at) {
        audit(table, recordId, column, null, newValue, username, at);
    }

    private void audit(String table, UUID recordId, String column, String oldValue, String newValue, String username, Instant at) {
        jdbc.update("""
                INSERT INTO bpa.audit_log (table_name, record_id, column_name, old_value, new_value, username, changed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""", table, recordId, column, oldValue, newValue, username, Timestamp.from(at));
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
