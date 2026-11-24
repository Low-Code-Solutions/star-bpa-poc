package com.lcs.star.bpa.persistence;

import com.lcs.star.bpa.api.generated.model.Activity;
import com.lcs.star.bpa.api.generated.model.Department;
import com.lcs.star.bpa.api.generated.model.Drawing;
import com.lcs.star.bpa.api.generated.model.Material;
import com.lcs.star.bpa.api.generated.model.Project;
import java.math.BigDecimal;
import java.sql.Date;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads the seeded POC master data (R__seed_reference_data.sql; data model §3.1-3.7) for the reference lists (09 S20; BPA-6,
 * BPA-46 Q4) and for the save step's "is this ref known" checks. Read-only: reference copies are written by the seed only.
 * Lists are ordered by ref (ASSUMPTION: no source orders them).
 */
@Repository
public class ReferenceRepository {

    private final JdbcTemplate jdbc;

    public ReferenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Each activity with its WBS's project and that project's Job Code (C9 hierarchy; D13 one Job Code per project). */
    public List<Activity> activities() {
        return jdbc.query("""
                SELECT a.activity_ref, a.activity_name, a.wbs_ref, w.project_ref, p.job_code_ref, a.fac_crpo, a.fac_type,
                       a.fac_name, a.discipline, a.baseline_start, a.baseline_end, a.budgeted_units
                FROM bpa.activity a
                JOIN bpa.wbs w ON w.wbs_ref = a.wbs_ref
                JOIN bpa.project_job_code p ON p.project_ref = w.project_ref
                ORDER BY a.activity_ref""", (rs, n) -> {
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
                    BigDecimal units = rs.getBigDecimal("budgeted_units"); // numeric(9,2): JSON 1200.00, read as 1200
                    activity.setBudgetedUnits(units);
                    return activity;
                });
    }

    public List<Drawing> drawings() {
        return jdbc.query("SELECT drawing_ref, title FROM bpa.drawing ORDER BY drawing_ref",
                (rs, n) -> new Drawing(rs.getString("drawing_ref"), rs.getString("title")));
    }

    public List<Material> materials() {
        return jdbc.query("SELECT material_ref, description FROM bpa.material ORDER BY material_ref",
                (rs, n) -> new Material(rs.getString("material_ref"), rs.getString("description")));
    }

    public List<Department> departments() {
        return jdbc.query("SELECT department_ref, name FROM bpa.department ORDER BY department_ref",
                (rs, n) -> new Department(rs.getString("department_ref"), rs.getString("name")));
    }

    public boolean activityExists(String activityRef) {
        return exists("SELECT count(*) FROM bpa.activity WHERE activity_ref = ?", activityRef);
    }

    public boolean drawingExists(String drawingRef) {
        return exists("SELECT count(*) FROM bpa.drawing WHERE drawing_ref = ?", drawingRef);
    }

    public boolean materialExists(String materialRef) {
        return exists("SELECT count(*) FROM bpa.material WHERE material_ref = ?", materialRef);
    }

    public boolean departmentExists(String departmentRef) {
        return exists("SELECT count(*) FROM bpa.department WHERE department_ref = ?", departmentRef);
    }

    /** BPA-76: the projects that can be assigned to a user (project_job_code.project_ref; D365 F&O Project Management, seeded copy), by reference. */
    public List<Project> projects() {
        return jdbc.query("SELECT project_ref FROM bpa.project_job_code ORDER BY project_ref", (rs, n) -> new Project(rs.getString("project_ref")));
    }

    public boolean projectExists(String projectRef) {
        return exists("SELECT count(*) FROM bpa.project_job_code WHERE project_ref = ?", projectRef);
    }

    private boolean exists(String sql, String ref) {
        Integer count = jdbc.queryForObject(sql, Integer.class, ref);
        return count != null && count > 0;
    }
}
