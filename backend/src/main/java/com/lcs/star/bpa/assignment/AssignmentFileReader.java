package com.lcs.star.bpa.assignment;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Reads the Worker assignment file (BPA-12; D59, D33 rules 1, 4, 5, 6; 09 S18): an .xlsx whose first sheet has a header row and one
 * row per assignment. Columns are found by their header, trimmed and case-insensitive, in any order (ASSUMPTION): Workpack number,
 * Badge No and Estimated hours are required, Employee name is ignored, Remarks is optional. Every value is read as the text the
 * user sees (raw, never parsed here: BPA-22 answers a bad number, data model 5.2) and a blank row is skipped (ASSUMPTION).
 *
 * <p>Only .xlsx is read: anything else (a renamed .csv, an old .xls, an empty or damaged file) is "File cannot be read". Pure, so a
 * unit test covers it; no formula is evaluated.
 */
public final class AssignmentFileReader {

    static final String CANNOT_BE_READ = "File cannot be read";
    static final String LAYOUT_NOT_RECOGNISED = "File layout not recognised";
    static final String NO_ROWS = "File has no rows";
    static final String TOO_MANY_ROWS = "File has too many rows";
    /** The most data rows one upload may carry (ASSUMPTION): one transaction and one response hold them all. */
    static final int MAX_ROWS = 5000;

    private static final String WORKPACK = "workpack number";
    private static final String BADGE = "badge no";
    private static final String HOURS = "estimated hours";
    private static final String REMARKS = "remarks";

    private static final Logger LOG = LoggerFactory.getLogger(AssignmentFileReader.class);

    static {
        // POI's zip-bomb guard allows an entry of up to 4 GB; an assignment file is small, so a sheet that unpacks to more than 50 MB is refused.
        ZipSecureFile.setMaxEntrySize(50L * 1024 * 1024);
    }

    private AssignmentFileReader() {
    }

    /**
     * @param fileOutcome the file-level line ("File has no rows", ...), or null when the rows were read
     * @param rows the data rows, in file order
     */
    public record Result(String fileOutcome, List<Row> rows) {
    }

    /** One data row as written in the file: raw text, never parsed here (data model 5.2). rowNumber is the spreadsheet row, 1-based. */
    public record Row(int rowNumber, String workpackNumber, String badgeNo, String estimatedHours, String remarks) {
    }

    public static Result read(byte[] file) {
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            if (workbook.getNumberOfSheets() == 0) {
                return new Result(CANNOT_BE_READ, List.of());
            }
            return rows(workbook.getSheetAt(0));
        } catch (Exception e) {
            // Not a workbook, an old .xls, empty or damaged: POI raises several kinds of exception; all are "cannot be read".
            LOG.debug("The assignment file could not be read", e);
            return new Result(CANNOT_BE_READ, List.of());
        }
    }

    private static Result rows(Sheet sheet) {
        DataFormatter formatter = new DataFormatter(Locale.ENGLISH);
        org.apache.poi.ss.usermodel.Row header = sheet.getRow(0);
        Map<String, Integer> columns = new HashMap<>();
        if (header != null) {
            for (Cell cell : header) {
                String name = formatter.formatCellValue(cell).trim().toLowerCase(Locale.ROOT);
                if (!name.isEmpty()) {
                    columns.putIfAbsent(name, cell.getColumnIndex());
                }
            }
        }
        if (!columns.containsKey(WORKPACK) || !columns.containsKey(BADGE) || !columns.containsKey(HOURS)) {
            return new Result(LAYOUT_NOT_RECOGNISED, List.of());
        }
        List<Row> rows = new ArrayList<>();
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            org.apache.poi.ss.usermodel.Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            String workpack = text(formatter, row, columns.get(WORKPACK));
            String badge = text(formatter, row, columns.get(BADGE));
            String hours = text(formatter, row, columns.get(HOURS));
            String remarks = text(formatter, row, columns.get(REMARKS));
            if (workpack == null && badge == null && hours == null && remarks == null) {
                continue; // a blank row (a row with only an Employee name is blank too: that column is ignored)
            }
            if (rows.size() == MAX_ROWS) {
                return new Result(TOO_MANY_ROWS, List.of());
            }
            rows.add(new Row(r + 1, workpack, badge, hours, remarks));
        }
        return rows.isEmpty() ? new Result(NO_ROWS, List.of()) : new Result(null, List.copyOf(rows));
    }

    /** The cell as displayed, trimmed; null when the column is absent or the cell empty. */
    private static String text(DataFormatter formatter, org.apache.poi.ss.usermodel.Row row, Integer column) {
        if (column == null) {
            return null;
        }
        Cell cell = row.getCell(column);
        if (cell == null) {
            return null;
        }
        String value = formatter.formatCellValue(cell).trim();
        return value.isEmpty() ? null : value;
    }
}
