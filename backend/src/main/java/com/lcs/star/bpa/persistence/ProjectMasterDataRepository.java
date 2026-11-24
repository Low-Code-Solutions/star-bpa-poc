package com.lcs.star.bpa.persistence;

import com.lcs.star.bpa.api.generated.model.Activity;
import com.lcs.star.bpa.api.generated.model.CbsActivity;
import com.lcs.star.bpa.api.generated.model.CuttingSheet;
import com.lcs.star.bpa.api.generated.model.ProjectDrawing;
import com.lcs.star.bpa.api.generated.model.ReservedMaterial;
import java.math.BigDecimal;
import java.sql.Date;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The master data of one project for the Projects and master data screen (BPA-77), read only: seeded copies of the systems of record (docs/03-sor-matrix.md). How
 * each kind reaches a project: an activity and its CBS through the WBS ({@code activity.wbs_ref → wbs.project_ref}); a drawing through {@code drawing.project_ref}
 * (V8, ASSUMPTION: no source gives the link); a reservation and a cutting sheet through their drawing. A drawing with no project is in no project's tabs. Every list
 * is ordered by its reference in byte order, so the order does not depend on the database collation (ASSUMPTION: no source orders them).
 */
@Repository
public class ProjectMasterDataRepository {

    private final JdbcTemplate jdbc;

    public ProjectMasterDataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The project's activities, with the WBS, the project and its Job Code (the columns of the BPA-6 reference list, filtered by project). */
    public List<Activity> activities(String projectRef) {
        return jdbc.query("""
                SELECT a.activity_ref, a.activity_name, a.wbs_ref, w.project_ref, p.job_code_ref, a.fac_crpo, a.fac_type,
                       a.fac_name, a.discipline, a.baseline_start, a.baseline_end, a.budgeted_units
                FROM bpa.activity a
                JOIN bpa.wbs w ON w.wbs_ref = a.wbs_ref
                JOIN bpa.project_job_code p ON p.project_ref = w.project_ref
                WHERE w.project_ref = ?
                ORDER BY a.activity_ref COLLATE "C\"""", (rs, n) -> {
                    Activity activity = new Activity(rs.getString("activity_ref"), rs.getString("activity_name"), rs.getString("wbs_ref"),
                            rs.getString("project_ref"), rs.getString("job_code_ref"));
                    activity.setFacCrpo(rs.getString("fac_crpo"));
                    activity.setFacType(rs.getString("fac_type"));
                    activity.setFacName(rs.getString("fac_name"));
                    activity.setDiscipline(rs.getString("discipline"));
                    Date start = rs.getDate("baseline_start");
                    Date end = rs.getDate("baseline_end");
                    activity.setBaselineStart(start == null ? null : start.toLocalDate());
                    activity.setBaselineEnd(end == null ? null : end.toLocalDate());
                    BigDecimal units = rs.getBigDecimal("budgeted_units");
                    activity.setBudgetedUnits(units);
                    return activity;
                }, projectRef);
    }

    /** The project's activities with their CBS code (cbs_activity: one row per activity). */
    public List<CbsActivity> costStructure(String projectRef) {
        return jdbc.query("""
                SELECT c.activity_ref, a.activity_name, c.cbs_code_ref
                FROM bpa.cbs_activity c
                JOIN bpa.activity a ON a.activity_ref = c.activity_ref
                JOIN bpa.wbs w ON w.wbs_ref = a.wbs_ref
                WHERE w.project_ref = ?
                ORDER BY c.activity_ref COLLATE "C\"""",
                (rs, n) -> new CbsActivity(rs.getString("activity_ref"), rs.getString("activity_name"), rs.getString("cbs_code_ref")), projectRef);
    }

    /** The project's drawings, each with its latest revision: the one most recently registered (D42); no revision fields when it has none. */
    public List<ProjectDrawing> drawings(String projectRef) {
        return jdbc.query("""
                SELECT d.drawing_ref, d.title, r.revision, r.status
                FROM bpa.drawing d
                LEFT JOIN LATERAL (SELECT revision, status FROM bpa.drawing_revision
                                   WHERE drawing_ref = d.drawing_ref ORDER BY registered_at DESC, revision DESC LIMIT 1) r ON true
                WHERE d.project_ref = ?
                ORDER BY d.drawing_ref COLLATE "C\"""", (rs, n) -> {
                    ProjectDrawing drawing = new ProjectDrawing(rs.getString("drawing_ref"), rs.getString("title"));
                    drawing.setLatestRevision(rs.getString("revision"));
                    drawing.setStatus(rs.getString("status"));
                    return drawing;
                }, projectRef);
    }

    /** The reservations on the project's drawings, with the description of the material. */
    public List<ReservedMaterial> reservedMaterials(String projectRef) {
        return jdbc.query("""
                SELECT m.traceability_number, m.drawing_ref, m.material_ref, mat.description
                FROM bpa.material_reservation m
                JOIN bpa.drawing d ON d.drawing_ref = m.drawing_ref
                JOIN bpa.material mat ON mat.material_ref = m.material_ref
                WHERE d.project_ref = ?
                ORDER BY m.traceability_number COLLATE "C", m.drawing_ref COLLATE "C", m.material_ref COLLATE "C\"""",
                (rs, n) -> new ReservedMaterial(rs.getString("traceability_number"), rs.getString("drawing_ref"), rs.getString("material_ref"),
                        rs.getString("description")), projectRef);
    }

    /** The cutting sheets of the project's drawings. */
    public List<CuttingSheet> cuttingSheets(String projectRef) {
        return jdbc.query("""
                SELECT c.cutting_sheet_ref, c.drawing_ref
                FROM bpa.cutting_sheet c
                JOIN bpa.drawing d ON d.drawing_ref = c.drawing_ref
                WHERE d.project_ref = ?
                ORDER BY c.cutting_sheet_ref COLLATE "C", c.drawing_ref COLLATE "C\"""",
                (rs, n) -> new CuttingSheet(rs.getString("cutting_sheet_ref"), rs.getString("drawing_ref")), projectRef);
    }
}
