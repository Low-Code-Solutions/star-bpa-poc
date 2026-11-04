package com.lcs.star.bpa.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The Create Work Pack task's data as {@code save-workpack} reads it (BPA-6; variable names from the BPA-26 allow-list and
 * docs/data-model.md §4.1-4.3). Any field may be missing: the validator reports what is.
 */
public record WorkPackInput(String activityRef, String drawingRef, String departmentRef, LocalDate completionDate,
        List<Phase> phases, List<Material> materials) {

    public record Phase(String phase, BigDecimal manhours) {
    }

    public record Material(String materialRef, String phase) {
    }
}
