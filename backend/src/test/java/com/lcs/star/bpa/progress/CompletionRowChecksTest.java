package com.lcs.star.bpa.progress;

import static org.assertj.core.api.Assertions.assertThat;

import com.lcs.star.bpa.progress.CompletionRowChecks.Raw;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * BPA-59: the row rules of the completion report, pure. Not Gherkin scenarios themselves; CompletionReportApiTest proves the same through the upload. The
 * texts in Jira are checked as written; the others are ASSUMPTIONs (plan Q9).
 */
class CompletionRowChecksTest {

    private static Optional<String> status(String workpack) {
        return switch (workpack) {
            case "WP-000457", "WP-000461" -> Optional.of("In Progress");
            case "WP-000480" -> Optional.of("Released");
            default -> Optional.empty();
        };
    }

    private static String check(String workpack, String completion, String hours, String source) {
        return CompletionRowChecks.check(new Raw(workpack, completion, hours, source), new HashSet<>(), CompletionRowChecksTest::status, "FMS");
    }

    @Test
    void aGoodRowIsRead() {
        assertThat(check("WP-000457", "40", "150", "FMS")).isEqualTo("Read");
        assertThat(check("WP-000457", "40.5", null, "fms")).as("Actual hours may be empty; the source is not case sensitive").isEqualTo("Read");
        assertThat(check("WP-000457", "40.50", "0", "FMS")).as("trailing zeros are not decimals").isEqualTo("Read");
    }

    @Test
    void over100AndNegativeAreReadForApproval() {
        assertThat(check("WP-000457", "120", "150", "FMS")).isEqualTo("Read");
        assertThat(check("WP-000457", "-5", "150", "FMS")).isEqualTo("Read");
    }

    @Test
    void requiredValuesAreChecked() {
        assertThat(check(null, "40", "1", "FMS")).isEqualTo("Workpack number is required");
        assertThat(check("WP-000457", null, "1", "FMS")).isEqualTo("Completion % is required");
        assertThat(check("WP-000457", "40", "1", null)).isEqualTo("Source is required");
    }

    @Test
    void theWorkPackMustExistAndBeInProgress() {
        assertThat(check("WP-999999", "40", "1", "FMS")).isEqualTo("Work Pack not found");
        assertThat(check("WP-000480", "40", "1", "FMS")).isEqualTo("Work Pack is not In Progress");
    }

    @Test
    void theCompletionMustBeANumberWithAtMostTwoDecimals() {
        assertThat(check("WP-000457", "forty", "1", "FMS")).isEqualTo("Completion % must be a number");
        assertThat(check("WP-000457", "1e2", "1", "FMS")).as("no exponent").isEqualTo("Completion % must be a number");
        assertThat(check("WP-000457", "40,5", "1", "FMS")).as("no comma").isEqualTo("Completion % must be a number");
        assertThat(check("WP-000457", "40.125", "1", "FMS")).isEqualTo("Completion % can have at most 2 decimal places");
        assertThat(check("WP-000457", "999.99", "1", "FMS")).isEqualTo("Read");
        assertThat(check("WP-000457", "1000", "1", "FMS")).isEqualTo("Completion % must be between -999.99 and 999.99");
        assertThat(check("WP-000457", "-1000", "1", "FMS")).isEqualTo("Completion % must be between -999.99 and 999.99");
    }

    @Test
    void actualHoursAreANumberOfZeroOrMoreWhenGiven() {
        assertThat(check("WP-000457", "40", "-1", "FMS")).isEqualTo("Actual hours must be a number of 0 or more");
        assertThat(check("WP-000457", "40", "many", "FMS")).isEqualTo("Actual hours must be a number of 0 or more");
        assertThat(check("WP-000457", "40", "0", "FMS")).isEqualTo("Read");
    }

    @Test
    void theSourceMustBeTheConfiguredOneAndIsShownAsWritten() {
        assertThat(check("WP-000457", "40", "1", "PMS")).isEqualTo("Source PMS is not the configured progress source");
        assertThat(check("WP-000457", "40", "1", "XYZ")).isEqualTo("Source XYZ is not the configured progress source");
        assertThat(CompletionRowChecks.check(new Raw("WP-000457", "40", "1", "PMS"), new HashSet<>(), CompletionRowChecksTest::status, "PMS")).isEqualTo("Read");
    }

    @Test
    void aSecondRowForTheSameWorkPackIsADuplicateWhateverTheFirstOutcome() {
        Set<String> seen = new HashSet<>();
        assertThat(CompletionRowChecks.check(new Raw("WP-000457", "forty", "1", "FMS"), seen, CompletionRowChecksTest::status, "FMS"))
                .isEqualTo("Completion % must be a number");
        assertThat(CompletionRowChecks.check(new Raw("WP-000457", "45", "1", "FMS"), seen, CompletionRowChecksTest::status, "FMS"))
                .isEqualTo("Duplicate row for WP-000457");
    }

    @Test
    void theKnownSourcesAreFmsAndPms() {
        assertThat(CompletionRowChecks.knownSource(" pms ")).contains("PMS");
        assertThat(CompletionRowChecks.knownSource("FMS")).contains("FMS");
        assertThat(CompletionRowChecks.knownSource("XYZ")).isEmpty();
        assertThat(CompletionRowChecks.knownSource(null)).isEmpty();
    }
}
