package com.lcs.star.bpa.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.lcs.star.bpa.domain.ReplacementPlanner.Current;
import com.lcs.star.bpa.domain.ReplacementPlanner.Kind;
import com.lcs.star.bpa.domain.ReplacementPlanner.Step;
import com.lcs.star.bpa.domain.ReplacementPlanner.Wanted;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * BPA-13: the replacement rule of {@code replace-worker-assignment}, with no database or process (the scenarios are in
 * WorkerReplacementApiTest). One decision per Worker, comparing the Work Pack's current assignments with the file's validated rows:
 * hours changed is Replaced, unchanged is left alone (no history, data model 5.4), new is Added, absent is Removed, and absent with hours
 * loaded is kept and flagged (D35, D36, D38). The order is the file's Workers first, then the current Workers the file left out.
 */
class ReplacementPlannerTest {

    private static final UUID E1021 = UUID.fromString("00000000-0000-0000-0000-000000001021");
    private static final UUID E1099 = UUID.fromString("00000000-0000-0000-0000-000000001099");
    private static final UUID E1150 = UUID.fromString("00000000-0000-0000-0000-000000001150");

    private static Current current(UUID id, String badge, String hours, boolean loaded) {
        return new Current(id, badge, new BigDecimal(hours), loaded);
    }

    private static Wanted wanted(UUID id, String badge, String hours) {
        return new Wanted(id, badge, new BigDecimal(hours), null);
    }

    private static List<String> summary(List<Step> steps) {
        return steps.stream().map(s -> s.kind() + " " + s.badgeNo() + " " + s.oldHours().stripTrailingZeros().toPlainString() + "->"
                + s.newHours().stripTrailingZeros().toPlainString()).toList();
    }

    @Test
    void theNewFileReplacesTheAssignmentAsInScenarioOne() {
        List<Step> steps = ReplacementPlanner.plan(
                List.of(current(E1021, "E1021", "40.00", false), current(E1099, "E1099", "60.00", false)),
                List.of(wanted(E1021, "E1021", "50.00"), wanted(E1150, "E1150", "30.00")));

        assertThat(summary(steps)).containsExactly("REPLACED E1021 40->50", "ADDED E1150 0->30", "REMOVED E1099 60->0");
    }

    @Test
    void unchangedHoursAreLeftAloneWhateverTheScaleOfTheNumbers() {
        List<Step> steps = ReplacementPlanner.plan(List.of(current(E1021, "E1021", "40.00", false)), List.of(wanted(E1021, "E1021", "40")));

        assertThat(steps).singleElement().satisfies(s -> assertThat(s.kind()).isEqualTo(Kind.UNCHANGED));
    }

    @Test
    void aWorkerLeftOutWhoHasHoursLoadedIsKeptAndFlaggedWithItsHoursUnchanged() {
        List<Step> steps = ReplacementPlanner.plan(
                List.of(current(E1021, "E1021", "40", false), current(E1099, "E1099", "60", true)),
                List.of(wanted(E1021, "E1021", "40"), wanted(E1150, "E1150", "30")));

        assertThat(summary(steps)).containsExactly("UNCHANGED E1021 40->40", "ADDED E1150 0->30", "KEPT_FLAGGED E1099 60->60");
    }

    @Test
    void aWorkerWithHoursLoadedWhoIsInTheFileIsUpdatedLikeAnyOther() {
        List<Step> steps = ReplacementPlanner.plan(List.of(current(E1099, "E1099", "60", true)), List.of(wanted(E1099, "E1099", "70")));

        assertThat(summary(steps)).containsExactly("REPLACED E1099 60->70");
    }

    @Test
    void anEmptyFileWouldRemoveEveryoneWithoutHoursLoadedAndKeepTheOthers() {
        List<Step> steps = ReplacementPlanner.plan(List.of(current(E1021, "E1021", "40", false), current(E1099, "E1099", "60", true)), List.of());

        assertThat(summary(steps)).containsExactly("REMOVED E1021 40->0", "KEPT_FLAGGED E1099 60->60");
    }

    @Test
    void aWorkPackWithNoAssignmentsGetsEveryWorkerAdded() {
        List<Step> steps = ReplacementPlanner.plan(List.of(), List.of(wanted(E1021, "E1021", "0.01"), wanted(E1099, "E1099", "99999.99")));

        assertThat(summary(steps)).containsExactly("ADDED E1021 0->0.01", "ADDED E1099 0->99999.99");
    }
}
