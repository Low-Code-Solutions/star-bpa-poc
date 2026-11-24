package com.lcs.star.bpa.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The save step's checks for a new Work Pack (BPA-6; D14, D16, D20, D56, D74; 09 S14). React checks the field rules first;
 * this re-checks all of them plus the cross-field rules, and returns every message that applies, each once, in this order
 * (ASSUMPTION: no source orders them): the required fields in the AC's table order, man hours, phases, materials, date, then
 * unknown references. Texts are the AC's; the ones marked ASSUMPTION below appear in no story.
 */
public final class WorkPackValidator {

    /** FRD-7 §7.4. */
    public static final List<String> PHASES = List.of("Engineering", "Cutting", "Welding", "Finishing");

    static final BigDecimal MAX_MANHOURS = new BigDecimal("99999.99");

    /** Which refs exist in the POC master data. */
    public interface KnownRefs {
        boolean activity(String activityRef);

        boolean drawing(String drawingRef);

        boolean material(String materialRef);

        boolean department(String departmentRef);

        /** BPA-78: the project an activity belongs to (activity to WBS to project); null when the activity is unknown. */
        default String activityProject(String activityRef) {
            return null;
        }
    }

    private WorkPackValidator() {
    }

    /** @param today the business date in Asia/Riyadh (D28, S9) */
    public static List<String> validate(WorkPackInput input, LocalDate today, KnownRefs known) {
        Set<String> messages = new LinkedHashSet<>();
        List<WorkPackInput.Phase> phases = input.phases() == null ? List.of() : input.phases();
        List<WorkPackInput.Material> materials = input.materials() == null ? List.of() : input.materials();

        // Required fields (AC "Required fields", D20).
        if (blank(input.activityRef())) {
            messages.add("Project activity is required");
        }
        if (blank(input.drawingRef())) {
            messages.add("Drawing is required");
        }
        if (materials.isEmpty()) {
            messages.add("At least one material is required");
        }
        if (materials.stream().anyMatch(m -> blank(m.phase()))) {
            messages.add("Select a phase for each material");
        }
        if (phases.stream().anyMatch(p -> p.manhours() == null)) {
            messages.add("Assign Manhour to each phase");
        }
        if (blank(input.departmentRef())) {
            messages.add("Department is required");
        }
        if (input.completionDate() == null) {
            messages.add("Date to complete is required");
        }

        // Man hours (D14): > 0, at most 2 decimals, at most 99,999.99.
        for (WorkPackInput.Phase phase : phases) {
            BigDecimal hours = phase.manhours();
            if (hours == null) {
                continue;
            }
            if (hours.signum() <= 0) {
                messages.add("Man hours must be greater than 0");
            } else if (hours.stripTrailingZeros().scale() > 2) {
                messages.add("Man hours can have at most 2 decimal places");
            } else if (hours.compareTo(MAX_MANHOURS) > 0) {
                messages.add("Man hours cannot exceed 99,999.99");
            }
        }

        // Phases: one of the four (ASSUMPTION texts), each once.
        Set<String> selected = new HashSet<>();
        for (WorkPackInput.Phase phase : phases) {
            if (blank(phase.phase())) {
                messages.add("Select a phase for each man-hour line"); // ASSUMPTION
            } else if (!PHASES.contains(phase.phase())) {
                messages.add("Phase " + phase.phase() + " is not a valid phase"); // ASSUMPTION
            } else if (!selected.add(phase.phase())) {
                messages.add("Phase " + phase.phase() + " is listed twice");
            }
        }

        // Materials: a material, a phase of this Work Pack (D16), once per phase (D74, N1).
        Set<String> materialInPhase = new HashSet<>();
        for (WorkPackInput.Material material : materials) {
            if (blank(material.materialRef())) {
                messages.add("Select a material for each material line"); // ASSUMPTION
                continue;
            }
            if (blank(material.phase())) {
                continue; // reported above
            }
            if (!selected.contains(material.phase())) {
                messages.add("Phase " + material.phase() + " is not selected for this Work Pack");
            } else if (!materialInPhase.add(material.materialRef() + "\u0000" + material.phase())) {
                messages.add("Material " + material.materialRef() + " is listed twice for phase " + material.phase());
            }
        }

        // Date to complete: not before today in Asia/Riyadh (D20, D28).
        if (input.completionDate() != null && input.completionDate().isBefore(today)) {
            messages.add("Date to complete cannot be in the past");
        }

        // References must exist in the POC master data (ASSUMPTION texts; the foreign keys are the backstop).
        if (!blank(input.activityRef()) && !known.activity(input.activityRef())) {
            messages.add("Project activity " + input.activityRef() + " is not known");
        }
        // BPA-78 (ASSUMPTION for the text): an instance started for a project (projectRef, from the start) takes only that project's activities. An instance started
        // before BPA-78 has no project and is not checked.
        if (!blank(input.projectRef()) && !blank(input.activityRef()) && known.activity(input.activityRef())) {
            String project = known.activityProject(input.activityRef());
            if (project != null && !project.equals(input.projectRef())) {
                messages.add("Project activity " + input.activityRef() + " is not in project " + input.projectRef());
            }
        }
        if (!blank(input.drawingRef()) && !known.drawing(input.drawingRef())) {
            messages.add("Drawing " + input.drawingRef() + " is not known");
        }
        if (!blank(input.departmentRef()) && !known.department(input.departmentRef())) {
            messages.add("Department " + input.departmentRef() + " is not known");
        }
        for (WorkPackInput.Material material : materials) {
            if (!blank(material.materialRef()) && !known.material(material.materialRef())) {
                messages.add("Material " + material.materialRef() + " is not known");
            }
        }
        return new ArrayList<>(messages);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
