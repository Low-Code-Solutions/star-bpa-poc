package com.lcs.star.bpa.assignment;

import static org.assertj.core.api.Assertions.assertThat;

import com.lcs.star.bpa.AssignmentFileBuilder;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/**
 * BPA-12: the official template in the repo, templates/worker-assignment-template.xlsx (09 S18, L6, ADR 0009). It is built by
 * {@code AssignmentFileBuilder.template()} (run its main to write it), and this test keeps the committed file honest: the sheet
 * "Assignment" with the five headers of D59 in order, a README sheet that marks the layout as an ASSUMPTION (D33 rule 1), and a file
 * the intake reads as "File has no rows" (a header only), not as a broken one.
 */
class AssignmentTemplateTest {

    private static final Path TEMPLATE = Path.of("..", "templates", "worker-assignment-template.xlsx");

    private static Workbook open() throws IOException {
        return new XSSFWorkbook(new ByteArrayInputStream(Files.readAllBytes(TEMPLATE)));
    }

    @Test
    void theTemplateExistsWithTheHeadersOfD59() throws IOException {
        assertThat(TEMPLATE).as("templates/worker-assignment-template.xlsx").exists();
        try (Workbook workbook = open()) {
            assertThat(workbook.getSheetAt(0).getSheetName()).isEqualTo("Assignment");
            Row header = workbook.getSheetAt(0).getRow(0);
            List<String> cells = new ArrayList<>();
            header.forEach(c -> cells.add(c.getStringCellValue()));
            assertThat(cells).containsExactly("Workpack number", "Badge No", "Estimated hours", "Employee name", "Remarks");
            assertThat(workbook.getSheetAt(0).getLastRowNum()).as("no data rows in the template").isZero();
        }
    }

    @Test
    void theReadmeSheetMarksTheLayoutAsAnAssumption() throws IOException {
        try (Workbook workbook = open()) {
            Sheet readme = workbook.getSheet("README");
            assertThat(readme).as("a README sheet").isNotNull();
            StringBuilder text = new StringBuilder();
            readme.forEach(row -> row.forEach(cell -> text.append(cell.getStringCellValue()).append('\n')));
            assertThat(text.toString()).contains("ASSUMPTION").contains("Workpack number").contains("Badge No").contains("Estimated hours");
        }
    }

    @Test
    void theIntakeReadsTheTemplateAsAnEmptyFileNotABrokenOne() throws IOException {
        assertThat(AssignmentFileReader.read(Files.readAllBytes(TEMPLATE)).fileOutcome()).isEqualTo("File has no rows");
    }

    @Test
    void theCommittedTemplateIsTheOneTheBuilderWrites() throws IOException {
        // A change to the layout in the builder fails here until the template is written again (AssignmentFileBuilder.main).
        try (Workbook committed = open(); Workbook built = new XSSFWorkbook(new ByteArrayInputStream(AssignmentFileBuilder.template()))) {
            assertThat(committed.getNumberOfSheets()).isEqualTo(built.getNumberOfSheets());
            for (int s = 0; s < built.getNumberOfSheets(); s++) {
                assertThat(committed.getSheetName(s)).isEqualTo(built.getSheetName(s));
                assertThat(committed.getSheetAt(s).getLastRowNum()).isEqualTo(built.getSheetAt(s).getLastRowNum());
                for (int r = 0; r <= built.getSheetAt(s).getLastRowNum(); r++) {
                    assertThat(committed.getSheetAt(s).getRow(r).getCell(0).getStringCellValue())
                            .isEqualTo(built.getSheetAt(s).getRow(r).getCell(0).getStringCellValue());
                }
            }
        }
    }
}
