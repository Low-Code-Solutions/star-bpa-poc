package com.lcs.star.bpa.persistence;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only access to the Look-Ahead PPC Plan-Dates of a Work Pack's drawing (BPA-11; D48). lookahead_item is a seeded copy of
 * D365 F&amp;O Project Control (PCON); the matching key is Design Dwgs = the Work Pack's drawing.
 */
@Repository
public class LookAheadRepository {

    private final JdbcTemplate jdbc;

    public LookAheadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The Work Pack's drawing and its three plan dates (each null when the drawing has no Look-Ahead row), or empty if there is no such Work Pack. */
    public Optional<LookAhead> findByWorkpackNumber(String workpackNumber) {
        return jdbc.query("""
                SELECT w.workpack_number, w.drawing_ref, l.shop_drg_mark_plan_date, l.assembly_plan_date, l.weld_map_plan_date
                FROM bpa.workpack w LEFT JOIN bpa.lookahead_item l ON l.design_dwg_ref = w.drawing_ref
                WHERE w.workpack_number = ?""",
                (rs, n) -> new LookAhead(rs.getString("workpack_number"), rs.getString("drawing_ref"),
                        rs.getObject("shop_drg_mark_plan_date", LocalDate.class), rs.getObject("assembly_plan_date", LocalDate.class),
                        rs.getObject("weld_map_plan_date", LocalDate.class)),
                workpackNumber).stream().findFirst();
    }

    public record LookAhead(String workpackNumber, String drawingRef, LocalDate shopDrgMarkPlanDate, LocalDate assemblyPlanDate,
            LocalDate weldMapPlanDate) {
    }
}
