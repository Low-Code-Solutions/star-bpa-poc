package com.lcs.star.bpa.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.lcs.star.bpa.api.generated.model.AssignmentFileResult;
import com.lcs.star.bpa.api.generated.model.AssignmentFileRow;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * BPA-12: the CSV of an assignment file's result (RFC 4180, the header names in the order of the result columns) and the name of the
 * attachment, which is built from the uploaded file's name and so must not carry a path, quotes or control characters (review).
 * A value that starts with = + - or @ is written as it is: protecting a spreadsheet from it would change the data in the download,
 * which is the Product Owner's call (noted in the PR).
 */
class AssignmentFileResultCsvConverterTest {

    private static AssignmentFileRow row(Integer number, String workpack, String badge, String name, String outcome, String wpOutcome) {
        AssignmentFileRow row = new AssignmentFileRow();
        row.setRowNumber(number);
        row.setWorkpackNumber(workpack);
        row.setBadgeNo(badge);
        row.setWorkerName(name);
        row.setOutcome(outcome);
        row.setWpOutcome(wpOutcome);
        return row;
    }

    private static AssignmentFileResult result(String outcome, AssignmentFileRow... rows) {
        AssignmentFileResult result = new AssignmentFileResult(UUID.randomUUID(), "assign-01.xlsx", OffsetDateTime.parse("2026-10-30T06:00:00Z"), List.of(rows));
        result.setOutcome(outcome);
        return result;
    }

    @Test
    void writesTheHeaderAndOneLinePerRowWithCrlf() {
        String csv = AssignmentFileResultCsvConverter.csv(result(null,
                row(2, "WP-000457", "E1021", "Worker E1021", "Pending", null), row(3, "WP-000457", "E9999", null, "Worker not in worker master data", null)));

        assertThat(csv).isEqualTo("rowNumber,workpackNumber,badgeNo,workerName,outcome,wpOutcome\r\n"
                + "2,WP-000457,E1021,Worker E1021,Pending,\r\n"
                + "3,WP-000457,E9999,,Worker not in worker master data,\r\n");
    }

    @Test
    void quotesValuesWithCommasQuotesAndLineBreaks() {
        String csv = AssignmentFileResultCsvConverter.csv(result(null,
                row(2, "WP-000457", "E1021", "Smith, \"Sam\"", "Rejected; see\nremarks", "Replacement not applied: file has rejected rows")));

        assertThat(csv).endsWith("2,WP-000457,E1021,\"Smith, \"\"Sam\"\"\",\"Rejected; see\nremarks\",Replacement not applied: file has rejected rows\r\n");
    }

    @Test
    void aFileLevelOutcomeIsTheOnlyLine() {
        assertThat(AssignmentFileResultCsvConverter.csv(result("File has no rows"))).isEqualTo("File has no rows\r\n");
    }

    @Test
    void theAttachmentNameIsTheUploadsNameWithoutAPathOrOddCharacters() {
        assertThat(AssignmentFileResultCsvConverter.downloadName("assign-01.xlsx")).isEqualTo("assign-01-result.csv");
        assertThat(AssignmentFileResultCsvConverter.downloadName("ASSIGN.XLSX")).isEqualTo("ASSIGN-result.csv");
        assertThat(AssignmentFileResultCsvConverter.downloadName("C:\\fakepath\\team a.xlsx")).isEqualTo("team a-result.csv");
        assertThat(AssignmentFileResultCsvConverter.downloadName("../../etc/x.xlsx")).isEqualTo("x-result.csv");
        assertThat(AssignmentFileResultCsvConverter.downloadName("a\";b\r\nSet-Cookie: x.xlsx")).doesNotContain("\"", ";", "\r", "\n");
        assertThat(AssignmentFileResultCsvConverter.downloadName(null)).isEqualTo("assignment-result.csv");
        assertThat(AssignmentFileResultCsvConverter.downloadName("  .xlsx")).as("a name with nothing left").isEqualTo("assignment-result.csv");
    }
}
