package com.lcs.star.bpa.print;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What the Workpack print shows (BPA-10, FRD-7 §7.3.3, §7.5.5 #14-15): read from the Work Pack, its phases, materials and schedule,
 * and the cutting sheets linked to its drawing (D30). Plain data, so the renderer needs no database.
 *
 * @param phases man hours per phase, in the order they are printed (the FRD order, Engineering to Finishing)
 * @param cuttingSheets the references of the cutting sheets linked to the drawing; empty prints "No cutting sheet available"
 */
public record PrintData(String workpackNumber, String activityRef, String drawingRef, String departmentRef, LocalDate completionDate,
        LocalDate plannedStart, LocalDate plannedEnd, List<PhaseLine> phases, List<MaterialLine> materials, List<String> cuttingSheets) {

    public record PhaseLine(String phase, BigDecimal manhours) {
    }

    public record MaterialLine(String materialRef, String phase) {
    }

    /** The sum of the phases' man hours, exact (BigDecimal). */
    public BigDecimal totalManhours() {
        return phases.stream().map(PhaseLine::manhours).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
