package com.lcs.star.bpa.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.lcs.star.bpa.AssignmentFileBuilder;
import com.lcs.star.bpa.assignment.AssignmentFileReader.Result;
import com.lcs.star.bpa.assignment.AssignmentFileReader.Row;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * BPA-12: reading the Worker assignment file (D59, D33 rules 1, 4, 5, 6), without a database or a process. Not Gherkin scenarios
 * themselves: BPA12_fileHasNoRows, BPA12_fileCannotBeRead and BPA12_fileLayoutNotRecognised (AssignmentFileApiTest) prove the file-level
 * lines through the upload. Files come from the AssignmentFileBuilder (09 S18), never from the demo files. The header matching (names
 * trimmed, case-insensitive, any order) and "blank rows are skipped" are ASSUMPTIONs.
 */
class AssignmentFileReaderTest {

    @Test
    void readsTheRowsOfTheTemplateLayout() {
        Result result = AssignmentFileReader.read(
                AssignmentFileBuilder.assignment().row("WP-000457", "E1021", 40).row("WP-000457", "E1099", 60).build());

        assertThat(result.fileOutcome()).isNull();
        assertThat(result.rows()).containsExactly(
                new Row(2, "WP-000457", "E1021", "40", null),
                new Row(3, "WP-000457", "E1099", "60", null));
    }

    @Test
    void matchesHeadersTrimmedCaseInsensitiveAndInAnyOrder() {
        byte[] bytes = AssignmentFileBuilder.assignment()
                .headers(" badge no ", "REMARKS", "Estimated Hours", "workpack NUMBER", "Employee name")
                .cells("E1021", "night shift", 12.5, "WP-000457", "ignored").build();

        Result result = AssignmentFileReader.read(bytes);

        assertThat(result.fileOutcome()).isNull();
        assertThat(result.rows()).containsExactly(new Row(2, "WP-000457", "E1021", "12.5", "night shift"));
    }

    @Test
    void numbersAreReadAsDisplayedAndTextIsKeptRaw() {
        // The hours are kept as the user wrote them (raw, data model 5.2): 40 not 40.0, and "abc" stays "abc" for BPA-22 to answer.
        Result result = AssignmentFileReader.read(AssignmentFileBuilder.assignment()
                .row("WP-000457", "E1021", 40).row("WP-000457", "E1099", "abc").row("WP-000457", "E1150", 7.25).build());

        assertThat(result.rows()).extracting(Row::estimatedHours).containsExactly("40", "abc", "7.25");
    }

    @Test
    void blankRowsAreSkippedAndRowNumbersAreTheSheetRows() {
        Result result = AssignmentFileReader.read(AssignmentFileBuilder.assignment()
                .row("WP-000457", "E1021", 40).blankRow().row("WP-000460", "E1099", 10).build());

        assertThat(result.rows()).extracting(Row::rowNumber, Row::workpackNumber)
                .containsExactly(tuple(2, "WP-000457"), tuple(4, "WP-000460"));
    }

    @Test
    void aRowWithAMissingCellIsStillARowAndTheCellIsNull() {
        Result result = AssignmentFileReader.read(AssignmentFileBuilder.assignment().cells(null, "E1021", 40, null, null).build());

        assertThat(result.rows()).containsExactly(new Row(2, null, "E1021", "40", null));
    }

    @Test
    void aHeaderOnlyFileHasNoRows() {
        assertThat(AssignmentFileReader.read(AssignmentFileBuilder.assignment().build())).isEqualTo(new Result("File has no rows", List.of()));
    }

    @Test
    void aFirstSheetWithoutTheRequiredColumnsIsNotRecognised() {
        // Workpack number, Badge No and Estimated hours are required (D59); Employee name and Remarks are not.
        assertThat(AssignmentFileReader.read(AssignmentFileBuilder.assignment().headers("Workpack number", "Badge No")
                .cells("WP-000457", "E1021").build()).fileOutcome()).isEqualTo("File layout not recognised");
        assertThat(AssignmentFileReader.read(AssignmentFileBuilder.assignment().headers("Work Pack", "Employee", "Hours")
                .cells("WP-000457", "E1021", 40).build()).fileOutcome()).isEqualTo("File layout not recognised");
        // The data on the second sheet does not count: only the first sheet is read.
        assertThat(AssignmentFileReader.read(AssignmentFileBuilder.assignment().emptySheetFirst()
                .row("WP-000457", "E1021", 40).build()).fileOutcome()).isEqualTo("File layout not recognised");
    }

    @Test
    void whatIsNotAnXlsxCannotBeRead() {
        assertThat(AssignmentFileReader.read(AssignmentFileBuilder.csvRenamedAsXlsx()).fileOutcome()).isEqualTo("File cannot be read");
        assertThat(AssignmentFileReader.read(AssignmentFileBuilder.legacyXls()).fileOutcome()).as("an old .xls is not read")
                .isEqualTo("File cannot be read");
        assertThat(AssignmentFileReader.read(new byte[0]).fileOutcome()).isEqualTo("File cannot be read");
        assertThat(AssignmentFileReader.read("not a workbook".getBytes()).fileOutcome()).isEqualTo("File cannot be read");
    }

    @Test
    void moreThanFiveThousandRowsIsRefusedAndExactlyFiveThousandIsRead() {
        // One upload is one transaction and one response, so the rows are capped (ASSUMPTION; added after review).
        AssignmentFileBuilder atTheLimit = AssignmentFileBuilder.assignment();
        for (int i = 0; i < 5000; i++) {
            atTheLimit.row("WP-000457", "E1021", 1);
        }
        Result read = AssignmentFileReader.read(atTheLimit.build());
        assertThat(read.fileOutcome()).isNull();
        assertThat(read.rows()).hasSize(5000);

        AssignmentFileBuilder over = AssignmentFileBuilder.assignment();
        for (int i = 0; i < 5001; i++) {
            over.row("WP-000457", "E1021", 1);
        }
        assertThat(AssignmentFileReader.read(over.build())).isEqualTo(new Result("File has too many rows", List.of()));
    }
}
