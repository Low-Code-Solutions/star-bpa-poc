package com.lcs.star.bpa.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The Create or Confirm Work Pack task's data as {@code save-workpack} reads it (BPA-6, BPA-23; variable names from the BPA-26
 * allow-list and docs/data-model.md §4.1-4.3). Any field may be missing: the validator reports what is.
 */
public record WorkPackInput(String activityRef, String drawingRef, String departmentRef, LocalDate completionDate,
        List<Phase> phases, List<Material> materials, String projectRef) {

    /** Without a project: the Confirm task, and an instance started before BPA-78 (no projectRef variable). */
    public WorkPackInput(String activityRef, String drawingRef, String departmentRef, LocalDate completionDate, List<Phase> phases, List<Material> materials) {
        this(activityRef, drawingRef, departmentRef, completionDate, phases, materials, null);
    }

    public record Phase(String phase, BigDecimal manhours) {
    }

    /**
     * A material line. {@code id} is the saved line's id, round-tripped by the Confirm screen so a correction updates the line
     * in place (data model A4, F13); null for a new line and on create.
     */
    public record Material(String materialRef, String phase, String id) {

        public Material(String materialRef, String phase) {
            this(materialRef, phase, null);
        }
    }
}
