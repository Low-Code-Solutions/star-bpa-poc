package com.lcs.star.bpa.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.lcs.star.bpa.api.generated.model.TimesheetFileResult;
import com.lcs.star.bpa.api.generated.model.TimesheetFileRow;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** BPA-52: the result download as CSV: columns, RFC 4180 quoting, the attachment name, and no cell of the upload that Excel would run as a formula. */
class TimesheetFileResultCsvConverterTest {

    private static TimesheetFileResult result(TimesheetFileRow... rows) {
        return new TimesheetFileResult(UUID.randomUUID(), "team-week47.xlsx", OffsetDateTime.parse("2026-11-18T06:00:00Z"), List.of(rows));
    }

    private static TimesheetFileRow row(String workpack, String hours, String remarks, String outcome) {
        TimesheetFileRow row = new TimesheetFileRow();
        row.setRowNumber(2);
        row.setWorkpackNumber(workpack);
        row.setBadgeNo("E1021");
        row.setWorkDate("16/11/2026");
        row.setPhase("Cutting");
        row.setHours(hours);
        row.setRemarks(remarks);
        row.setOutcome(outcome);
        return row;
    }

    @Test
    void theColumnsAndQuotingFollowTheResult() {
        String csv = TimesheetFileResultCsvConverter.csv(result(row("WP-000457", "7.5", "Night shift, late", "Saved")));

        assertThat(csv).isEqualTo("rowNumber,workpackNumber,badgeNo,workDate,phase,hours,remarks,outcome\r\n"
                + "2,WP-000457,E1021,16/11/2026,Cutting,7.5,\"Night shift, late\",Saved\r\n");
    }

    @Test
    void aFileLevelOutcomeIsOneLine() {
        TimesheetFileResult result = result();
        result.setOutcome("File has no rows");

        assertThat(TimesheetFileResultCsvConverter.csv(result)).isEqualTo("File has no rows\r\n");
    }

    @Test
    void aCellThatExcelWouldRunAsAFormulaIsNeutralised() {
        String csv = TimesheetFileResultCsvConverter.csv(result(row("=HYPERLINK(\"http://example.com\")", "-5", "+cmd|' /C calc'!A0", "@SUM(1)")));

        assertThat(csv).contains("'=HYPERLINK(").contains(",-5,").contains("'+cmd|").contains("'@SUM(1)");
        assertThat(csv).as("a plain negative number stays a number").doesNotContain("'-5");
    }

    @Test
    void theDownloadNameIsAPlainFileName() {
        assertThat(TimesheetFileResultCsvConverter.downloadName("C:\\Users\\x\\team-week47.xlsx")).isEqualTo("team-week47-result.csv");
        assertThat(TimesheetFileResultCsvConverter.downloadName("a\"b;c.csv")).isEqualTo("abc-result.csv");
        assertThat(TimesheetFileResultCsvConverter.downloadName(null)).isEqualTo("timesheet-result.csv");
    }
}
