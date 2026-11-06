package com.lcs.star.bpa;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Builds Worker assignment files for the tests (09 S18, ADR 0009): the layout of D59, sheet "Assignment", a header row, then one row
 * per assignment. Tests never use the demo files in the repo; every file comes from here or from src/test/resources/. It also builds
 * the broken shapes the file-level checks answer (a header only, missing columns, a wrong first sheet, something that is not an
 * .xlsx) and the official template itself, which {@code main} writes to templates/ (run it once, or after a layout change; the
 * AssignmentTemplateTest then keeps the committed file honest).
 */
public final class AssignmentFileBuilder {

    /** The columns of the template, in the order of D59. */
    public static final List<String> HEADERS = List.of("Workpack number", "Badge No", "Estimated hours", "Employee name", "Remarks");

    private List<String> headers = HEADERS;
    private String sheetName = "Assignment";
    private boolean emptySheetFirst;
    private final List<List<Object>> rows = new ArrayList<>();

    public static AssignmentFileBuilder assignment() {
        return new AssignmentFileBuilder();
    }

    /** Other column headers (a different order, other case or spaces, or columns missing). */
    public AssignmentFileBuilder headers(String... headers) {
        this.headers = List.of(headers);
        return this;
    }

    public AssignmentFileBuilder sheetName(String name) {
        this.sheetName = name;
        return this;
    }

    /** An empty sheet in front, so the sheet with the data is not the first one. */
    public AssignmentFileBuilder emptySheetFirst() {
        this.emptySheetFirst = true;
        return this;
    }

    /** One data row: the cells in the order of the header; a Number becomes a numeric cell, null leaves the cell empty. */
    public AssignmentFileBuilder cells(Object... cells) {
        rows.add(Arrays.asList(cells));
        return this;
    }

    /** One assignment row in the template's own columns: Workpack number, Badge No, Estimated hours (the rest left empty). */
    public AssignmentFileBuilder row(String workpackNumber, String badgeNo, Object estimatedHours) {
        return cells(workpackNumber, badgeNo, estimatedHours, null, null);
    }

    public AssignmentFileBuilder blankRow() {
        rows.add(List.of());
        return this;
    }

    public byte[] build() {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (emptySheetFirst) {
                workbook.createSheet("Notes");
            }
            Sheet sheet = workbook.createSheet(sheetName);
            Row header = sheet.createRow(0);
            for (int c = 0; c < headers.size(); c++) {
                header.createCell(c).setCellValue(headers.get(c));
            }
            for (int r = 0; r < rows.size(); r++) {
                List<Object> cells = rows.get(r);
                if (cells.isEmpty()) {
                    continue; // a blank row: nothing written, so the next row keeps its spreadsheet row number
                }
                Row row = sheet.createRow(r + 1);
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

    /** A CSV renamed to .xlsx: not a workbook at all ("File cannot be read"). */
    public static byte[] csvRenamedAsXlsx() {
        return "Workpack number,Badge No,Estimated hours\nWP-000457,E1021,40\n".getBytes(StandardCharsets.UTF_8);
    }

    /** An old .xls workbook (not .xlsx): the intake reads .xlsx only. */
    public static byte[] legacyXls() {
        try (Workbook workbook = new HSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Assignment");
            Row header = sheet.createRow(0);
            for (int c = 0; c < HEADERS.size(); c++) {
                header.createCell(c).setCellValue(HEADERS.get(c));
            }
            sheet.createRow(1).createCell(0).setCellValue("WP-000457");
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The official template (S18, L6, ADR 0009): sheet "Assignment" with the five headers, and a README sheet marking the layout as an ASSUMPTION. */
    public static byte[] template() {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Assignment");
            Row header = sheet.createRow(0);
            for (int c = 0; c < HEADERS.size(); c++) {
                header.createCell(c).setCellValue(HEADERS.get(c));
                sheet.setColumnWidth(c, 5200);
            }
            Sheet readme = workbook.createSheet("README");
            List<String> lines = List.of(
                    "STAR BPA POC: Worker assignment template",
                    "ASSUMPTION: this layout is a working assumption (D33 rule 1, D59) until STAR sends the real FMS template.",
                    "Fill in the sheet \"Assignment\", one row per Worker, starting in row 2. Keep the header row.",
                    "Workpack number (required): for example WP-000457. One file may cover several Work Packs.",
                    "Badge No (required): the Worker's Badge No, for example E1021.",
                    "Estimated hours (required): a number greater than 0.",
                    "Employee name (optional): ignored on import; the name comes from the Worker master data.",
                    "Remarks (optional): at most 250 characters.",
                    "Upload the file in the portal (Assignment file, Upload). The result is shown row by row and can be downloaded.");
            for (int i = 0; i < lines.size(); i++) {
                readme.createRow(i).createCell(0).setCellValue(lines.get(i));
            }
            readme.setColumnWidth(0, 18000);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Writes the template: {@code java … AssignmentFileBuilder <target file>}. Run from the build, not by a test. */
    public static void main(String[] args) throws IOException {
        Path target = Path.of(args[0]);
        try (OutputStream out = Files.newOutputStream(target)) {
            out.write(template());
        }
        System.out.println("wrote " + target.toAbsolutePath());
    }
}
