package com.lcs.star.bpa.fileintake;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * The format-level part of a spreadsheet upload, shared by the time sheet upload (BPA-52, {@code TimesheetFileReader}) and the completion report upload
 * (BPA-59, plan section 2): an .xlsx whose first sheet, or a .csv, has a header row and one row per record. Columns are found by their header, trimmed and
 * case-insensitive, in any order (BPA-52 plan Q1): the caller names the required columns and the optional ones. Every value is the text the user sees (raw,
 * never parsed here); a real Excel date cell is turned into dd/mm/yyyy; a blank row is skipped.
 *
 * <p>The format follows the file name's extension, .xlsx or .csv (ASSUMPTION): anything else, and any .xlsx that is not a workbook or any .csv that is not
 * UTF-8 text, is "File cannot be read". A .csv is comma separated, UTF-8 (a byte order mark is ignored), with RFC 4180 quoting (ASSUMPTION, Jira decision b
 * of BPA-52). Pure, so a unit test covers it; no formula is evaluated. This class is BPA-52's reader with the columns made parameters: its behaviour is
 * unchanged, and BPA-52's tests run against it through {@code TimesheetFileReader}.
 */
public final class TabularFileReader {

    public static final String CANNOT_BE_READ = "File cannot be read";
    public static final String LAYOUT_NOT_RECOGNISED = "File layout not recognised";
    public static final String NO_ROWS = "File has no rows";
    public static final String TOO_MANY_ROWS = "File has too many rows";
    /** The most data rows one upload may carry (ASSUMPTION, as the assignment file). */
    public static final int MAX_ROWS = 5000;

    private static final DateTimeFormatter EXCEL_DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu");
    private static final Logger LOG = LoggerFactory.getLogger(TabularFileReader.class);

    static {
        ZipSecureFile.setMaxEntrySize(50L * 1024 * 1024);
    }

    private TabularFileReader() {
    }

    /** @param fileOutcome the file-level line ("File has no rows", ...), or null when the rows were read */
    public record Result(String fileOutcome, List<TabularRow> rows) {
    }

    /**
     * One data row as written in the file. {@code rowNumber} is the spreadsheet or file line, 1-based (the header is row 1); {@code values} holds the trimmed
     * text of each required and optional column, keyed by its lower-case name, or null when the cell is empty or the optional column is missing.
     */
    public record TabularRow(int rowNumber, Map<String, String> values) {
        public String get(String column) {
            return values.get(column);
        }
    }

    public static Result read(String fileName, byte[] file, List<String> required, List<String> optional) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".xlsx")) {
            return readXlsx(file, required, optional);
        }
        if (name.endsWith(".csv")) {
            return readCsv(file, required, optional);
        }
        return new Result(CANNOT_BE_READ, List.of());
    }

    // --- .xlsx ------------------------------------------------------------------------------------------------------------

    private static Result readXlsx(byte[] file, List<String> required, List<String> optional) {
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            if (workbook.getNumberOfSheets() == 0) {
                return new Result(CANNOT_BE_READ, List.of());
            }
            return xlsxRows(workbook.getSheetAt(0), required, optional);
        } catch (Exception e) {
            // Not a workbook, an old .xls, empty or damaged: POI raises several kinds of exception; all are "cannot be read".
            LOG.debug("The file could not be read as .xlsx", e);
            return new Result(CANNOT_BE_READ, List.of());
        }
    }

    private static Result xlsxRows(Sheet sheet, List<String> required, List<String> optional) {
        DataFormatter formatter = new DataFormatter(Locale.ENGLISH);
        Row header = sheet.getRow(0);
        List<String> headerCells = new ArrayList<>();
        if (header != null) {
            for (int c = 0; c < header.getLastCellNum(); c++) {
                Cell cell = header.getCell(c);
                headerCells.add(cell == null ? "" : formatter.formatCellValue(cell));
            }
        }
        Map<String, Integer> columns = columns(headerCells, required);
        if (columns == null) {
            return new Result(LAYOUT_NOT_RECOGNISED, List.of());
        }
        List<TabularRow> rows = new ArrayList<>();
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            List<String> cells = new ArrayList<>();
            for (int c = 0; c <= row.getLastCellNum(); c++) {
                cells.add(cellText(formatter, row.getCell(c)));
            }
            TabularRow parsed = rowOf(r + 1, cells, columns, required, optional);
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

    private static Result readCsv(byte[] file, List<String> required, List<String> optional) {
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
        Map<String, Integer> columns = columns(lines.get(0), required);
        if (columns == null) {
            return new Result(LAYOUT_NOT_RECOGNISED, List.of());
        }
        List<TabularRow> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            TabularRow parsed = rowOf(i + 1, lines.get(i), columns, required, optional);
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
    private static Map<String, Integer> columns(List<String> header, List<String> required) {
        Map<String, Integer> columns = new HashMap<>();
        for (int c = 0; c < header.size(); c++) {
            String name = header.get(c).trim().toLowerCase(Locale.ROOT);
            if (!name.isEmpty()) {
                columns.putIfAbsent(name, c);
            }
        }
        return columns.keySet().containsAll(required) ? columns : null;
    }

    private static TabularRow rowOf(int rowNumber, List<String> cells, Map<String, Integer> columns, List<String> required, List<String> optional) {
        Map<String, String> values = new LinkedHashMap<>();
        boolean blank = true;
        for (List<String> names : List.of(required, optional)) {
            for (String name : names) {
                String value = value(cells, columns.get(name));
                values.put(name, value);
                blank &= value == null;
            }
        }
        return blank ? null : new TabularRow(rowNumber, values); // a blank row is skipped
    }

    private static String value(List<String> cells, Integer column) {
        if (column == null || column >= cells.size()) {
            return null;
        }
        String value = cells.get(column).trim();
        return value.isEmpty() ? null : value;
    }
}
