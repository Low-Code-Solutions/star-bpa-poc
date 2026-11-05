package com.lcs.star.bpa.persistence;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only queries behind ServiceTask_EvaluateReleaseInputs (BPA-8): the two booleans the release-check DMN decides on.
 * Both read POC master data seeded from their real SOR (D365 F&O PPC reservations and Document Control revisions, D54, C15).
 */
@Repository
public class ReleaseInputsRepository {

    private final JdbcTemplate jdbc;

    public ReleaseInputsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** True when every material line of the Work Pack has a reservation for the Work Pack's drawing and that material. */
    public boolean materialsReserved(UUID workpackId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT NOT EXISTS (
                    SELECT 1 FROM bpa.workpack w
                    JOIN bpa.workpack_material m ON m.workpack_id = w.id
                    WHERE w.id = ? AND NOT EXISTS (
                        SELECT 1 FROM bpa.material_reservation r
                        WHERE r.drawing_ref = w.drawing_ref AND r.material_ref = m.material_ref))""", Boolean.class, workpackId));
    }

    /**
     * True when the latest revision (by registered_at) that is not Superseded has status IFC or As-Built (D1, D10). False when
     * the drawing has no such revision (ASSUMPTION in the story: no revision rows means not approved). Ties on registered_at go to the higher revision letter (ASSUMPTION).
     */
    public boolean drawingApproved(UUID workpackId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT COALESCE((
                    SELECT dr.status IN ('IFC', 'As-Built')
                    FROM bpa.workpack w
                    JOIN bpa.drawing_revision dr ON dr.drawing_ref = w.drawing_ref
                    WHERE w.id = ? AND dr.status <> 'Superseded'
                    ORDER BY dr.registered_at DESC, dr.revision DESC LIMIT 1), false)""", Boolean.class, workpackId));
    }
}
