package com.lcs.star.bpa.timesheet;

import static org.assertj.core.api.Assertions.assertThat;

import com.lcs.star.bpa.TimesheetFileBuilder;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/**
 * BPA-52: the time sheet reader's own rules (the scenarios are in TimesheetFileApiTest): headers by name, a .csv's quoting and byte order mark, a real Excel
 * date cell, and the committed template, whose header row the reader must recognise (the template and the reader name the same columns).
 */
class TimesheetFileReaderTest {

    @Test
    void theCommittedTemplateIsReadAsAnEmptyTimeSheet() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("templates/timesheet-upload-template.xlsx")) {
            assertThat(in).as("templates/timesheet-upload-template.xlsx on the classpath").isNotNull();
            byte[] template = in.readAllBytes();

            assertThat(TimesheetFileReader.read("timesheet-upload-template.xlsx", template).fileOutcome()).isEqualTo("File has no rows");
            try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(template))) {
                Sheet first = workbook.getSheetAt(0);
                assertThat(first.getSheetName()).isEqualTo("Timesheet");
                Row header = first.getRow(0);
                List<String> cells = new java.util.ArrayList<>();
                for (Cell cell : header) {
                    cells.add(cell.getStringCellValue());
                }
                assertThat(cells).isEqualTo(TimesheetFileBuilder.HEADERS);
            }
        }
    }

    @Test
    void csvQuotingByteOrderMarkAndBlankLinesAreRead() {
        String csv = "﻿Workpack number,Badge No,Work date,Phase,Hours,Remarks\r\n"
                + "WP-000457,E1021,16/11/2026,Cutting,8,\"Night shift, late\"\r\n"
                + "\r\n"
                + "WP-000457,E1099,16/11/2026,Welding,7.5,\"said \"\"ok\"\"\"\n";

        TimesheetFileReader.Result result = TimesheetFileReader.read("team.csv", csv.getBytes(StandardCharsets.UTF_8));

        assertThat(result.fileOutcome()).isNull();
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().get(0).remarks()).isEqualTo("Night shift, late");
        assertThat(result.rows().get(0).rowNumber()).isEqualTo(2);
        assertThat(result.rows().get(1).remarks()).isEqualTo("said \"ok\"");
        assertThat(result.rows().get(1).rowNumber()).as("the blank line counts as a line").isEqualTo(4);
    }

    @Test
    void aRealExcelDateCellReadsAsDayMonthYear() throws Exception {
        byte[] file;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Timesheet");
            Row header = sheet.createRow(0);
            List<String> names = TimesheetFileBuilder.HEADERS;
            for (int c = 0; c < names.size(); c++) {
                header.createCell(c).setCellValue(names.get(c));
            }
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("WP-000457");
            row.createCell(1).setCellValue("E1021");
            Cell date = row.createCell(2);
            date.setCellValue(LocalDate.of(2026, 11, 16));
            var style = workbook.createCellStyle();
            style.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));
            date.setCellStyle(style);
            row.createCell(3).setCellValue("Cutting");
            row.createCell(4).setCellValue(8);
            workbook.write(out);
            file = out.toByteArray();
        }

        assertThat(TimesheetFileReader.read("dated.xlsx", file).rows().get(0).workDate()).isEqualTo("16/11/2026");
    }

    @Test
    void anUnknownExtensionOrBrokenFileCannotBeRead() {
        assertThat(TimesheetFileReader.read("notes.txt", "x".getBytes(StandardCharsets.UTF_8)).fileOutcome()).isEqualTo("File cannot be read");
        assertThat(TimesheetFileReader.read("notes.csv", new byte[] {(byte) 0xC3, (byte) 0x28}).fileOutcome()).as("not UTF-8").isEqualTo("File cannot be read");
        assertThat(TimesheetFileReader.read("notes.xlsx", TimesheetFileBuilder.notASpreadsheet()).fileOutcome()).isEqualTo("File cannot be read");
    }
}
