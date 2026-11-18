package com.lcs.star.bpa.timesheet;

import java.io.ByteArrayInputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads a time sheet upload (BPA-52; E2-D4; ADR 0009 pattern, as the assignment file reader of BPA-12): an .xlsx whose first sheet, or a .csv, has a header
 * row and one row per Worker, date and phase. Columns are found by their header, trimmed and case-insensitive, in any order (plan Q1, as the assignment
 * file): Workpack number, Badge No, Work date, Phase and Hours are required, Remarks is optional. Every value is the text the user sees (raw, never parsed
 * here: the service answers a bad date or number, data model §13.2.3); a real Excel date cell is turned into dd/mm/yyyy; a blank row is skipped.
 *
 * <p>The format follows the file name's extension, .xlsx or .csv (ASSUMPTION): anything else, and any .xlsx that is not a workbook or any .csv that is not
 * UTF-8 text, is "File cannot be read". A .csv is comma separated, UTF-8 (a byte order mark is ignored), with RFC 4180 quoting (ASSUMPTION, Jira decision b).
 * Pure, so a unit test covers it; no formula is evaluated.
 */
public final class TimesheetFileReader {

    static final String CANNOT_BE_READ = "File cannot be read";
    static final String LAYOUT_NOT_RECOGNISED = "File layout not recognised";
    static final String NO_ROWS = "File has no rows";
    static final String TOO_MANY_ROWS = "File has too many rows";
    /** The most data rows one upload may carry (ASSUMPTION, as the assignment file). */
    static final int MAX_ROWS = 5000;

    /** The columns of the template, as the reader finds them (lower case). */
    public static final List<String> REQUIRED = List.of("workpack number", "badge no", "work date", "phase", "hours");
    static final String REMARKS = "remarks";

    private static final DateTimeFormatter EXCEL_DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu");
    private static final Logger LOG = LoggerFactory.getLogger(TimesheetFileReader.class);

    static {
        ZipSecureFile.setMaxEntrySize(50L * 1024 * 1024);
    }

    private TimesheetFileReader() {
    }

    /** @param fileOutcome the file-level line ("File has no rows", ...), or null when the rows were read */
    public record Result(String fileOutcome, List<Row> rows) {
    }

    /** One data row as written in the file, raw text. rowNumber is the spreadsheet or file line, 1-based (the header is row 1). */
    public record Row(int rowNumber, String workpackNumber, String badgeNo, String workDate, String phase, String hours, String remarks) {
    }

    public static Result read(String fileName, byte[] file) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".xlsx")) {
            return readXlsx(file);
        }
        if (name.endsWith(".csv")) {
            return readCsv(file);
        }
        return new Result(CANNOT_BE_READ, List.of());
    }

    // --- .xlsx ------------------------------------------------------------------------------------------------------------

    private static Result readXlsx(byte[] file) {
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            if (workbook.getNumberOfSheets() == 0) {
                return new Result(CANNOT_BE_READ, List.of());
            }
            return xlsxRows(workbook.getSheetAt(0));
        } catch (Exception e) {
            // Not a workbook, an old .xls, empty or damaged: POI raises several kinds of exception; all are "cannot be read".
            LOG.debug("The time sheet could not be read as .xlsx", e);
            return new Result(CANNOT_BE_READ, List.of());
        }
    }

    private static Result xlsxRows(Sheet sheet) {
        DataFormatter formatter = new DataFormatter(Locale.ENGLISH);
        org.apache.poi.ss.usermodel.Row header = sheet.getRow(0);
        List<String> headerCells = new ArrayList<>();
        if (header != null) {
            for (int c = 0; c < header.getLastCellNum(); c++) {
                Cell cell = header.getCell(c);
                headerCells.add(cell == null ? "" : formatter.formatCellValue(cell));
            }
        }
        Map<String, Integer> columns = columns(headerCells);
        if (columns == null) {
            return new Result(LAYOUT_NOT_RECOGNISED, List.of());
        }
        List<Row> rows = new ArrayList<>();
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            org.apache.poi.ss.usermodel.Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            List<String> cells = new ArrayList<>();
            for (int c = 0; c <= row.getLastCellNum(); c++) {
                cells.add(cellText(formatter, row.getCell(c)));
            }
            Row parsed = rowOf(r + 1, cells, columns);
            if (parsed == null) {
                continue;
            }
            if (rows.size() == MAX_ROWS) {
                return new Result(TOO_MANY_ROWS, List.of());
            }
            rows.add(parsed);
        }
        return rows.isEmpty() ? new Result(NO_ROWS, List.of()) : new Result(null, List.copyOf(rows));
    }

    /** The cell as displayed, trimmed; a real Excel date becomes dd/mm/yyyy; empty is "". */
    private static String cellText(DataFormatter formatter, Cell cell) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return EXCEL_DATE.format(cell.getLocalDateTimeCellValue().toLocalDate());
        }
        return formatter.formatCellValue(cell).trim();
    }

    // --- .csv -------------------------------------------------------------------------------------------------------------

    private static Result readCsv(byte[] file) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(file)).toString();
        } catch (CharacterCodingException e) {
            return new Result(CANNOT_BE_READ, List.of());
        }
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        List<List<String>> lines = csvRecords(text);
        if (lines.isEmpty()) {
            return new Result(CANNOT_BE_READ, List.of());
        }
        Map<String, Integer> columns = columns(lines.get(0));
        if (columns == null) {
            return new Result(LAYOUT_NOT_RECOGNISED, List.of());
        }
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            Row parsed = rowOf(i + 1, lines.get(i), columns);
            if (parsed == null) {
                continue;
            }
            if (rows.size() == MAX_ROWS) {
                return new Result(TOO_MANY_ROWS, List.of());
            }
            rows.add(parsed);
        }
        return rows.isEmpty() ? new Result(NO_ROWS, List.of()) : new Result(null, List.copyOf(rows));
    }

    /** RFC 4180: commas separate, a field may be quoted (with "" for a quote and line breaks inside), CRLF or LF ends a record. */
    static List<List<String>> csvRecords(String text) {
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(ch);
                }
            } else if (ch == '"' && field.length() == 0) {
                quoted = true;
                any = true;
            } else if (ch == ',') {
                record.add(field.toString().trim());
                field.setLength(0);
                any = true;
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                // a blank line is kept as an empty record, so a blank line counts in the row number (the reader skips an empty row); a quoted field with a line break is one record
                if (any || field.length() > 0) {
                    record.add(field.toString().trim());
                }
                records.add(record);
                record = new ArrayList<>();
                field.setLength(0);
                any = false;
            } else {
                field.append(ch);
                any = true;
            }
        }
        if (any || field.length() > 0) {
            record.add(field.toString().trim());
            records.add(record);
        }
        return records;
    }

    // --- shared -----------------------------------------------------------------------------------------------------------

    /** The header's column index per lower-case name, or null when a required column is missing. */
    private static Map<String, Integer> columns(List<String> header) {
        Map<String, Integer> columns = new HashMap<>();
        for (int c = 0; c < header.size(); c++) {
            String name = header.get(c).trim().toLowerCase(Locale.ROOT);
            if (!name.isEmpty()) {
                columns.putIfAbsent(name, c);
            }
        }
        return columns.keySet().containsAll(REQUIRED) ? columns : null;
    }

    private static Row rowOf(int rowNumber, List<String> cells, Map<String, Integer> columns) {
        String workpack = value(cells, columns.get("workpack number"));
        String badge = value(cells, columns.get("badge no"));
        String date = value(cells, columns.get("work date"));
        String phase = value(cells, columns.get("phase"));
        String hours = value(cells, columns.get("hours"));
        String remarks = value(cells, columns.get(REMARKS));
        if (workpack == null && badge == null && date == null && phase == null && hours == null && remarks == null) {
            return null; // a blank row
        }
        return new Row(rowNumber, workpack, badge, date, phase, hours, remarks);
    }

    private static String value(List<String> cells, Integer column) {
        if (column == null || column >= cells.size()) {
            return null;
        }
        String value = cells.get(column).trim();
        return value.isEmpty() ? null : value;
    }
}
