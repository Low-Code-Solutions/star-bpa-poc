package com.lcs.star.bpa.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * BPA-78 (plan, save backstop): when the instance carries the project its start was for, an Activity of another project is refused at save, with a message next to
 * "Project activity X is not known" (ASSUMPTION for the text). An instance started before BPA-78 has no project and is not checked. WorkPackValidator had no unit
 * test before; the BPA-6 rules are covered through WorkPackApiTest, so these cover only what BPA-78 adds.
 */
class WorkPackValidatorTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-11-24");

    /** The seed's activity to project map: A-2201 and A-2301 are on PRJ-139, A-2206 on PRJ-140; A-9999 is unknown. */
    private static final WorkPackValidator.KnownRefs KNOWN = new WorkPackValidator.KnownRefs() {
        private final Map<String, String> projects = Map.of("A-2201", "PRJ-139", "A-2301", "PRJ-139", "A-2206", "PRJ-140");

        @Override
        public boolean activity(String activityRef) {
            return projects.containsKey(activityRef);
        }

        @Override
        public boolean drawing(String drawingRef) {
            return true;
        }

        @Override
        public boolean material(String materialRef) {
            return true;
        }

        @Override
        public boolean department(String departmentRef) {
            return true;
        }

        @Override
        public String activityProject(String activityRef) {
            return projects.get(activityRef);
        }
    };

    private static WorkPackInput input(String activityRef, String projectRef) {
        return new WorkPackInput(activityRef, "DWG-118", "Fabrication", LocalDate.parse("2026-11-30"),
                List.of(new WorkPackInput.Phase("Cutting", new BigDecimal("150"))), List.of(new WorkPackInput.Material("MAT-PIPE-06", "Cutting")), projectRef);
    }

    @Test
    void anActivityOfAnotherProjectIsRefused() {
        List<String> messages = WorkPackValidator.validate(input("A-2206", "PRJ-139"), TODAY, KNOWN);

        assertThat(messages).containsExactly("Project activity A-2206 is not in project PRJ-139");
    }

    @Test
    void anActivityOfTheProjectIsAccepted() {
        assertThat(WorkPackValidator.validate(input("A-2201", "PRJ-139"), TODAY, KNOWN)).isEmpty();
        assertThat(WorkPackValidator.validate(input("A-2206", "PRJ-140"), TODAY, KNOWN)).isEmpty();
    }

    /** An instance started before BPA-78 has no projectRef: no project check, as before. */
    @Test
    void noProjectMeansNoProjectCheck() {
        assertThat(WorkPackValidator.validate(input("A-2206", null), TODAY, KNOWN)).isEmpty();
        assertThat(WorkPackValidator.validate(input("A-2206", ""), TODAY, KNOWN)).isEmpty();
    }

    /** An unknown activity is "not known", not also "not in the project". */
    @Test
    void anUnknownActivityIsNotAlsoNotInTheProject() {
        List<String> messages = WorkPackValidator.validate(input("A-9999", "PRJ-139"), TODAY, KNOWN);

        assertThat(messages).containsExactly("Project activity A-9999 is not known");
    }
}
