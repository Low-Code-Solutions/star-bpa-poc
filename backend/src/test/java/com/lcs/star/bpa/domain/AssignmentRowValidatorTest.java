package com.lcs.star.bpa.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.lcs.star.bpa.domain.AssignmentRowValidator.Row;
import com.lcs.star.bpa.domain.AssignmentRowValidator.Verdict;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * BPA-22: the row checks of {@code validate-worker-assignment}, with no database or process (the scenarios are in
 * WorkerAssignmentApiTest). The order of the checks when a row has several faults, and that the duplicate check counts a row whatever
 * else is wrong with it, are ASSUMPTIONs (no source orders them; plan Q1); the texts are the story's (D14, D32, D59).
 */
class AssignmentRowValidatorTest {

    private static final Set<String> KNOWN = Set.of("E1021", "E1099", "E2001");

    private static Row row(int number, String badge, String hours, String remarks) {
        return new Row(number, badge, hours, remarks);
    }

    private static String outcome(String badge, String hours, String remarks) {
        return AssignmentRowValidator.validate(List.of(row(2, badge, hours, remarks)), KNOWN).get(0).outcome();
    }

    @Test
    void aValidRowIsAssignedWithItsHoursAtTwoDecimals() {
        Verdict verdict = AssignmentRowValidator.validate(List.of(row(2, "E1021", "40", null)), KNOWN).get(0);

        assertThat(verdict.outcome()).isEqualTo("Assigned");
        assertThat(verdict.accepted()).isTrue();
        assertThat(verdict.hours()).isEqualByComparingTo(new BigDecimal("40.00"));
        assertThat(verdict.hours().scale()).as("numeric(7,2)").isEqualTo(2);
    }

    @Test
    void aBadgeNoIsRequiredAndNotInTheMasterDataIsRejected() {
        assertThat(outcome(null, "40", null)).isEqualTo("Badge No is required");
        assertThat(outcome("", "40", null)).isEqualTo("Badge No is required");
        assertThat(outcome("E9999", "40", null)).isEqualTo("Worker not in worker master data");
        assertThat(outcome("e1021", "40", null)).as("exact match, no case folding (Q2)").isEqualTo("Worker not in worker master data");
    }

    @Test
    void theHoursRulesFollowD14InOrder() {
        assertThat(outcome("E1021", null, null)).isEqualTo("Estimated hours is required");
        assertThat(outcome("E1021", "abc", null)).isEqualTo("Estimated hours must be a number");
        assertThat(outcome("E1021", "1,000", null)).isEqualTo("Estimated hours must be a number");
        assertThat(outcome("E1021", "NaN", null)).isEqualTo("Estimated hours must be a number");
        assertThat(outcome("E1021", "0", null)).isEqualTo("Estimated hours must be greater than 0");
        assertThat(outcome("E1021", "0.000", null)).isEqualTo("Estimated hours must be greater than 0");
        assertThat(outcome("E1021", "-5", null)).isEqualTo("Estimated hours must be greater than 0");
        assertThat(outcome("E1021", "0.001", null)).isEqualTo("Estimated hours can have at most 2 decimal places");
        assertThat(outcome("E1021", "99999.991", null)).as("decimals before the maximum").isEqualTo("Estimated hours can have at most 2 decimal places");
        assertThat(outcome("E1021", "100000", null)).isEqualTo("Estimated hours cannot exceed 99,999.99");
        assertThat(outcome("E1021", "0.01", null)).isEqualTo("Assigned");
        assertThat(outcome("E1021", "99999.99", null)).isEqualTo("Assigned");
        assertThat(outcome("E1021", "40.10", null)).as("trailing zeros do not count as decimals").isEqualTo("Assigned");
        assertThat(outcome("E1021", "40.100", null)).isEqualTo("Assigned");
    }

    @Test
    void remarksAreCountedInCharactersNotUtf16Units() {
        assertThat(outcome("E1021", "40", "x".repeat(250))).isEqualTo("Assigned");
        assertThat(outcome("E1021", "40", "x".repeat(251))).isEqualTo("Remarks can be at most 250 characters");
        assertThat(outcome("E1021", "40", "😀".repeat(250))).as("250 emoji, 500 UTF-16 units").isEqualTo("Assigned");
        assertThat(outcome("E1021", "40", "😀".repeat(251))).isEqualTo("Remarks can be at most 250 characters");
    }

    @Test
    void theSameWorkerTwiceRejectsBothRowsWhateverElseIsWrongWithThem() {
        List<Verdict> verdicts = AssignmentRowValidator.validate(
                List.of(row(2, "E1021", "40", null), row(3, "E1021", "abc", null), row(4, "E1099", "10", null)), KNOWN);

        assertThat(verdicts).extracting(Verdict::outcome).containsExactly(
                "Worker listed twice for this Work Pack", "Worker listed twice for this Work Pack", "Assigned");
        assertThat(verdicts.get(0).hours()).isNull();
    }

    @Test
    void aBlankBadgeNoTwiceIsNotADuplicateAndAnUnknownOneTwiceIs() {
        assertThat(AssignmentRowValidator.validate(List.of(row(2, null, "40", null), row(3, "", "40", null)), KNOWN))
                .extracting(Verdict::outcome).containsExactly("Badge No is required", "Badge No is required");
        assertThat(AssignmentRowValidator.validate(List.of(row(2, "E9999", "40", null), row(3, "E9999", "40", null)), KNOWN))
                .extracting(Verdict::outcome).containsExactly("Worker listed twice for this Work Pack", "Worker listed twice for this Work Pack");
    }

    @Test
    void aRejectedRowDoesNotStopTheNextOne() {
        assertThat(AssignmentRowValidator.validate(List.of(row(2, "E9999", "40", null), row(3, "E1021", "40", "night shift")), KNOWN))
                .extracting(Verdict::outcome).containsExactly("Worker not in worker master data", "Assigned");
    }
}
