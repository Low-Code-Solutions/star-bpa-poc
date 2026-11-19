package com.lcs.star.bpa;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Builds completion report files for the tests (BPA-59; ADR 0009 pattern, as {@link TimesheetFileBuilder}): the layout of the template, sheet
 * "Completion", a header row, then one row per Work Pack, as .xlsx or .csv. Tests never use the demo files in templates/demo: every file comes from here.
 * A Number becomes a numeric cell (so 40.125 is a number, not text); a String stays text. {@link #main} writes the two committed test fixtures under
 * src/test/resources/fms/ (comment 10490): {@code fms-completion-wk47.xlsx} (the rows of the story's first scenario) and {@code r.xlsx} (a text file
 * renamed, "notes.txt" renamed to "r.xlsx").
 */
public final class CompletionReportBuilder {

    /** The columns of the template, in order. */
    public static final List<String> HEADERS = List.of("Workpack number", "Completion %", "Actual hours", "Source");

    private List<String> headers = HEADERS;
    private final List<List<Object>> rows = new ArrayList<>();

    public static CompletionReportBuilder report() {
        return new CompletionReportBuilder();
    }

    /** Other column headers: another case or order (the rows keep the template's order of cells), or columns missing. */
    public CompletionReportBuilder headers(String... headers) {
        this.headers = List.of(headers);
        return this;
    }

    /** One data row in the template's columns; null leaves a cell empty. */
    public CompletionReportBuilder row(String workpackNumber, Object completion, Object actualHours, String source) {
        rows.add(Arrays.asList(workpackNumber, completion, actualHours, source));
        return this;
    }

    /** One data row whose cells follow the headers given with {@link #headers} (another order, or columns missing); null leaves a cell empty. */
    public CompletionReportBuilder rowAs(Object... cells) {
        rows.add(Arrays.asList(cells));
        return this;
    }

    /** A row with the default source, FMS. */
    public CompletionReportBuilder row(String workpackNumber, Object completion, Object actualHours) {
        return row(workpackNumber, completion, actualHours, "FMS");
    }

    public byte[] xlsx() {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Completion");
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

    /** Writes the committed fixtures: {@code java ... CompletionReportBuilder backend/src/test/resources/fms}. */
    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0] : "src/test/resources/fms");
        Files.createDirectories(dir);
        Files.write(dir.resolve("fms-completion-wk47.xlsx"), report().row("WP-000457", 40, 150).row("WP-000461", 100, 95).xlsx());
        Files.write(dir.resolve("r.xlsx"), "These are notes, not a workbook.\n".getBytes(StandardCharsets.UTF_8));
    }
}
