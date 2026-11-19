package com.lcs.star.bpa.fms;

import static org.assertj.core.api.Assertions.assertThat;

import com.lcs.star.bpa.fms.FmsHoursFileWriter.FmsRow;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/**
 * BPA-58: the weekly FMS hours file as the writer makes it (the layout of templates/fms-hours-export-template.xlsx, an ASSUMPTION until STAR sends its
 * template). Not Gherkin scenarios themselves; FmsHoursExportApiTest proves the same through the cut-off and the download.
 */
class FmsHoursFileWriterTest {

    static final List<String> HEADER = List.of("Type", "Date", "Badge No.", "Employment Type", "Project", "Activity", "Cost Centre", "CBS", "CBS Cost Centre", "Hrs");

    private static final Path TEMPLATE = Path.of("..", "templates", "fms-hours-export-template.xlsx");

    private static FmsRow row(String badge, boolean agency, String costCentre, String cbs, String hours) {
        return new FmsRow(LocalDate.of(2026, 11, 16), badge, agency, "PRJ-139", "A-2201", costCentre, cbs, "Fabrication", new BigDecimal(hours));
    }

    private static Sheet sheet(Workbook book) {
        return book.getSheet("FMS Hours");
    }

    @Test
    void theHeaderRowIsTheTenColumnsOfTheStory() throws IOException {
        try (Workbook book = new XSSFWorkbook(new ByteArrayInputStream(FmsHoursFileWriter.write(List.of())))) {
            assertThat(book.getNumberOfSheets()).isEqualTo(1);
            assertThat(texts(sheet(book).getRow(0))).isEqualTo(HEADER);
            assertThat(sheet(book).getLastRowNum()).as("no data rows").isZero();
        }
        assertThat(FmsHoursFileWriter.HEADER).isEqualTo(HEADER);
    }

    @Test
    void aRowCarriesTheValuesAndTheHoursAreANumberOfTwoDecimals() throws IOException {
        try (Workbook book = new XSSFWorkbook(new ByteArrayInputStream(FmsHoursFileWriter.write(List.of(
                row("E1021", false, "STRP01", "CBS-139-PIP-01", "8"), row("E2001", true, "STRP02", "CBS-139-PIP-01", "7.5")))))) {
            assertThat(texts(sheet(book).getRow(1))).containsExactly("FMS", "16/11/2026", "E1021", "Direct", "PRJ-139", "A-2201", "STRP01", "CBS-139-PIP-01",
                    "Fabrication", "8.00");
            assertThat(texts(sheet(book).getRow(2)).subList(2, 4)).containsExactly("E2001", "Agency");
            assertThat(sheet(book).getRow(2).getCell(9).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(sheet(book).getRow(2).getCell(9).getNumericCellValue()).isEqualTo(7.5);
            assertThat(texts(sheet(book).getRow(2)).get(9)).isEqualTo("7.50");
        }
    }

    @Test
    void aNullCostCentreAndCbsAreBlankCells() throws IOException {
        try (Workbook book = new XSSFWorkbook(new ByteArrayInputStream(FmsHoursFileWriter.write(List.of(row("E1150", false, null, null, "4")))))) {
            Row data = sheet(book).getRow(1);
            assertThat(data.getCell(6)).as("Cost Centre").isNull();
            assertThat(data.getCell(7)).as("CBS").isNull();
            assertThat(texts(data).get(8)).isEqualTo("Fabrication");
        }
    }

    @Test
    void theRowsAreWrittenInTheOrderGiven() throws IOException {
        try (Workbook book = new XSSFWorkbook(new ByteArrayInputStream(FmsHoursFileWriter.write(List.of(
                row("E1099", false, "STRP01", null, "1"), row("E1021", false, "STRP01", null, "2")))))) {
            assertThat(texts(sheet(book).getRow(1)).get(2)).isEqualTo("E1099");
            assertThat(texts(sheet(book).getRow(2)).get(2)).isEqualTo("E1021");
        }
    }

    /** The committed template (the layout's picture) has the writer's header row, and its README sheet says the layout is an ASSUMPTION. */
    @Test
    void theCommittedTemplateHasTheWritersHeader() throws IOException {
        assertThat(TEMPLATE).as("templates/fms-hours-export-template.xlsx").exists();
        try (Workbook book = new XSSFWorkbook(Files.newInputStream(TEMPLATE))) {
            assertThat(texts(sheet(book).getRow(0))).isEqualTo(FmsHoursFileWriter.HEADER);
            StringBuilder readme = new StringBuilder();
            book.getSheet("README").forEach(r -> r.forEach(c -> readme.append(c.getStringCellValue()).append('\n')));
            assertThat(readme.toString()).contains("ASSUMPTION");
        }
    }

    private static List<String> texts(Row row) {
        DataFormatter formatter = new DataFormatter();
        List<String> values = new ArrayList<>();
        for (int c = 0; c < 10; c++) {
            Cell cell = row.getCell(c);
            values.add(cell == null ? "" : formatter.formatCellValue(cell));
        }
        return values;
    }
}
