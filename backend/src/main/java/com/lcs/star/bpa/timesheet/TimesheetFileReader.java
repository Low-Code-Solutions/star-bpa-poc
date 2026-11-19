package com.lcs.star.bpa.timesheet;

import com.lcs.star.bpa.fileintake.TabularFileReader;
import java.util.List;

/**
 * Reads a time sheet upload (BPA-52; E2-D4; ADR 0009 pattern, as the assignment file reader of BPA-12): an .xlsx whose first sheet, or a .csv, has a header
 * row and one row per Worker, date and phase. Columns are found by their header, trimmed and case-insensitive, in any order (plan Q1, as the assignment
 * file): Workpack number, Badge No, Work date, Phase and Hours are required, Remarks is optional. Every value is the text the user sees (raw, never parsed
 * here: the service answers a bad date or number, data model §13.2.3); a real Excel date cell is turned into dd/mm/yyyy; a blank row is skipped.
 *
 * <p>Since BPA-59 the format-level reading (extension, .xlsx and .csv, header matching, blank rows, the file outcomes) is {@link TabularFileReader},
 * shared with the completion report upload; this class names the time sheet's columns and gives the rows their typed shape. Pure, so a unit test covers it.
 */
public final class TimesheetFileReader {

    /** The columns of the template, as the reader finds them (lower case). */
    public static final List<String> REQUIRED = List.of("workpack number", "badge no", "work date", "phase", "hours");
    static final String REMARKS = "remarks";

    private TimesheetFileReader() {
    }

    /** @param fileOutcome the file-level line ("File has no rows", ...), or null when the rows were read */
    public record Result(String fileOutcome, List<Row> rows) {
    }

    /** One data row as written in the file, raw text. rowNumber is the spreadsheet or file line, 1-based (the header is row 1). */
    public record Row(int rowNumber, String workpackNumber, String badgeNo, String workDate, String phase, String hours, String remarks) {
    }

    public static Result read(String fileName, byte[] file) {
        TabularFileReader.Result read = TabularFileReader.read(fileName, file, REQUIRED, List.of(REMARKS));
        return new Result(read.fileOutcome(), read.rows().stream().map(r -> new Row(r.rowNumber(), r.get("workpack number"), r.get("badge no"),
                r.get("work date"), r.get("phase"), r.get("hours"), r.get(REMARKS))).toList());
    }
}
