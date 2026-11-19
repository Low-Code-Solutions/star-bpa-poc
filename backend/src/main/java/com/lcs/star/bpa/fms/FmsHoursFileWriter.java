package com.lcs.star.bpa.fms;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Writes one weekly FMS hours file (BPA-58; E2-08) in the layout of {@code templates/fms-hours-export-template.xlsx}: sheet {@code FMS Hours}, header in
 * row 1, one row per Approved line. The layout is the Epic 2 working assumption until STAR sends its FMS upload template. Date is text {@code dd/MM/yyyy}
 * (as the demo file); Hrs is a number shown to 2 decimals (plan Q10). A blank Cost Centre or CBS is a blank cell, not an empty string (D40). The rows
 * come in the order to be written (Date, Badge No., Project, Activity); the writer does not sort. Pure, so a unit test covers it.
 */
public final class FmsHoursFileWriter {

    public static final String SHEET = "FMS Hours";
    public static final List<String> HEADER = List.of("Type", "Date", "Badge No.", "Employment Type", "Project", "Activity", "Cost Centre", "CBS",
            "CBS Cost Centre", "Hrs");

    static final String TYPE = "FMS"; // ASSUMPTION (FRD-5-3 §5.3.8 "Timesheet Category")
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** One line of the file; {@code costCentre} and {@code cbs} are null when the source has none. */
    public record FmsRow(LocalDate workDate, String badgeNo, boolean agency, String project, String activity, String costCentre, String cbs,
            String cbsCostCentre, BigDecimal hours) {
    }

    private FmsHoursFileWriter() {
    }

    public static byte[] write(List<FmsRow> rows) {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet(SHEET);
            Font bold = book.createFont();
            bold.setBold(true);
            CellStyle header = book.createCellStyle();
            header.setFont(bold);
            DataFormat formats = book.createDataFormat();
            CellStyle hours = book.createCellStyle();
            hours.setDataFormat(formats.getFormat("0.00"));
            Row head = sheet.createRow(0);
            for (int c = 0; c < HEADER.size(); c++) {
                head.createCell(c).setCellValue(HEADER.get(c));
                head.getCell(c).setCellStyle(header);
                sheet.setColumnWidth(c, 14 * 256);
            }
            sheet.setColumnWidth(7, 19 * 256);
            int r = 1;
            for (FmsRow row : rows) {
                Row line = sheet.createRow(r++);
                line.createCell(0).setCellValue(TYPE);
                line.createCell(1).setCellValue(row.workDate().format(DATE));
                line.createCell(2).setCellValue(row.badgeNo());
                line.createCell(3).setCellValue(row.agency() ? "Agency" : "Direct");
                line.createCell(4).setCellValue(row.project());
                line.createCell(5).setCellValue(row.activity());
                if (row.costCentre() != null) {
                    line.createCell(6).setCellValue(row.costCentre());
                }
                if (row.cbs() != null) {
                    line.createCell(7).setCellValue(row.cbs());
                }
                line.createCell(8).setCellValue(row.cbsCostCentre());
                line.createCell(9).setCellValue(row.hours().setScale(2, java.math.RoundingMode.HALF_UP).doubleValue());
                line.getCell(9).setCellStyle(hours);
            }
            book.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("The FMS hours file could not be written", e);
        }
    }
}
