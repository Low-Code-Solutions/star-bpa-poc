package com.lcs.star.bpa;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Builds time sheet upload files for the tests (BPA-52; ADR 0009 pattern): the layout of the template, sheet "Timesheet", a header row, then one
 * row per Worker, date and phase, as .xlsx or as .csv (comma, UTF-8, header row). Tests never use the demo files in templates/demo: every file comes
 * from here. Dates are text cells, "dd/mm/yyyy", as the demo files and the template write them; a Number becomes a numeric cell.
 */
public final class TimesheetFileBuilder {

    /** The columns of the template, in order. */
    public static final List<String> HEADERS = List.of("Workpack number", "Badge No", "Work date", "Phase", "Hours", "Remarks");

    private List<String> headers = HEADERS;
    private final List<List<Object>> rows = new ArrayList<>();

    public static TimesheetFileBuilder timesheet() {
        return new TimesheetFileBuilder();
    }

    /** Other column headers: another case, another order, or columns missing. */
    public TimesheetFileBuilder headers(String... headers) {
        this.headers = List.of(headers);
        return this;
    }

    /** One data row in the template's columns; null leaves a cell empty. */
    public TimesheetFileBuilder row(String workpackNumber, String badgeNo, String workDate, String phase, Object hours, String remarks) {
        rows.add(Arrays.asList(workpackNumber, badgeNo, workDate, phase, hours, remarks));
        return this;
    }

    public TimesheetFileBuilder row(String workpackNumber, String badgeNo, String workDate, String phase, Object hours) {
        return row(workpackNumber, badgeNo, workDate, phase, hours, null);
    }

    public byte[] xlsx() {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Timesheet");
            Row header = sheet.createRow(0);
            for (int c = 0; c < headers.size(); c++) {
                header.createCell(c).setCellValue(headers.get(c));
            }
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                List<Object> cells = rows.get(r);
                for (int c = 0; c < cells.size(); c++) {
                    Object value = cells.get(c);
                    if (value instanceof Number number) {
                        row.createCell(c).setCellValue(number.doubleValue());
                    } else if (value != null) {
                        row.createCell(c).setCellValue(value.toString());
                    }
                }
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The same rows as a .csv: comma, UTF-8, a header row, a value with a comma or a quote quoted (RFC 4180). */
    public byte[] csv() {
        StringBuilder out = new StringBuilder(String.join(",", headers)).append("\r\n");
        for (List<Object> cells : rows) {
            out.append(cells.stream().map(v -> v == null ? "" : quote(v.toString())).collect(Collectors.joining(","))).append("\r\n");
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String quote(String value) {
        return value.contains(",") || value.contains("\"") || value.contains("\n") ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }

    /** Text that is not a workbook, named .xlsx by the caller: "File cannot be read". */
    public static byte[] notASpreadsheet() {
        return "These are my notes, not a spreadsheet.\n".getBytes(StandardCharsets.UTF_8);
    }
}
