package com.lcs.star.bpa.print;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/**
 * BPA-10: the Workpack print's rendering (09 S1, S3), without a database. The BPA10_ scenario tests (PrintApiTest) prove the same
 * content through the real process; these pin what only the renderer can: the text, the embedded font and the same bytes for the same
 * data and instant (the PDF creation date comes from the instant, so a re-run gives the same sha256). Per S1 the tests assert the
 * extracted text with PDFBox, not the bytes of the layout.
 */
class WorkPackPrintRendererTest {

    static final Instant AT = Instant.parse("2026-10-21T06:00:00Z"); // 09:00 in Riyadh
    private final WorkPackPrintRenderer renderer = new WorkPackPrintRenderer(ZoneId.of("Asia/Riyadh"));

    private static PrintData data(BigDecimal cutting, List<String> cuttingSheets) {
        return new PrintData("WP-000457", "A-2201", "DWG-118", "Fabrication", LocalDate.parse("2026-11-06"),
                LocalDate.parse("2026-11-03"), LocalDate.parse("2026-11-09"), // planned end differs from the date to complete (review)
                List.of(new PrintData.PhaseLine("Cutting", cutting), new PrintData.PhaseLine("Welding", new BigDecimal("250.00"))),
                List.of(new PrintData.MaterialLine("MAT-PIPE-06", "Cutting"), new PrintData.MaterialLine("MAT-FLG-02", "Welding")),
                cuttingSheets);
    }

    /** The document /ID as hex text (a COSArray has no value equality). */
    private static String idText(PDDocument document) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < document.getDocument().getDocumentID().size(); i++) {
            text.append(java.util.HexFormat.of().formatHex(((org.apache.pdfbox.cos.COSString) document.getDocument().getDocumentID().get(i)).getBytes()));
        }
        return text.toString();
    }

    private static String text(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    @Test
    void rendersTheFieldsOfTheStory() throws IOException {
        String text = text(renderer.render(data(new BigDecimal("150.00"), List.of("CS-3310")), AT));

        // Each value next to its label (the planned end differs from the date to complete, so a swapped or missing row fails).
        assertThat(text).contains("WP-000457");
        assertThat(text).containsPattern("Activity\\s+A-2201");
        assertThat(text).containsPattern("Drawing\\s+DWG-118");
        assertThat(text).containsPattern("Department\\s+Fabrication");
        assertThat(text).containsPattern("Date to complete\\s+06 Nov 2026");
        assertThat(text).containsPattern("Planned start\\s+03 Nov 2026");
        assertThat(text).containsPattern("Planned end\\s+09 Nov 2026");
        assertThat(text).containsPattern("Cutting\\s+150\\b");
        assertThat(text).containsPattern("Welding\\s+250\\b");
        assertThat(text).containsPattern("Total\\s+400\\b");
        assertThat(text).containsPattern("MAT-PIPE-06\\s+Cutting");
        assertThat(text).containsPattern("MAT-FLG-02\\s+Welding");
        assertThat(text).contains("CS-3310");
        assertThat(text).doesNotContain("No cutting sheet available");
    }

    @Test
    void manHoursPrintAsEntered() throws IOException {
        // The database holds numeric(7,2): 150.50 comes back with two decimals, and prints as entered (150.5), total 400.5.
        String text = text(renderer.render(data(new BigDecimal("150.50"), List.of("CS-3310")), AT));

        assertThat(text).contains("150.5").doesNotContain("150.50");
        assertThat(text).contains("400.5");
    }

    @Test
    void noCuttingSheetPrintsTheNotice() throws IOException {
        String text = text(renderer.render(data(new BigDecimal("150"), List.of()), AT));

        assertThat(text).contains("No cutting sheet available");
    }

    @Test
    void theSameDataAndInstantGiveTheSameBytes() throws Exception {
        byte[] first = renderer.render(data(new BigDecimal("150"), List.of("CS-3310")), AT);
        // More than a second of wall clock between the renders, so a date or id that came from the wall clock (to the second) and not
        // from the instant would differ (added after review).
        Thread.sleep(1100);
        byte[] second = renderer.render(data(new BigDecimal("150"), List.of("CS-3310")), AT);

        assertThat(first).isNotEmpty().isEqualTo(second);
        try (PDDocument a = Loader.loadPDF(first); PDDocument b = Loader.loadPDF(second)) {
            assertThat(a.getDocument().getDocumentID()).as("the document /ID").isNotNull();
            assertThat(idText(a)).isNotEmpty().isEqualTo(idText(b));
            assertThat(a.getDocumentInformation().getModificationDate().toInstant()).isEqualTo(AT);
        }
    }

    @Test
    void theCreationDateIsTheInstantGiven() throws IOException {
        Instant other = AT.plusSeconds(3600);

        byte[] first = renderer.render(data(new BigDecimal("150"), List.of("CS-3310")), AT);
        byte[] later = renderer.render(data(new BigDecimal("150"), List.of("CS-3310")), other);

        assertThat(later).isNotEqualTo(first);
        try (PDDocument document = Loader.loadPDF(first)) {
            assertThat(document.getDocumentInformation().getCreationDate().toInstant()).isEqualTo(AT);
        }
        try (PDDocument document = Loader.loadPDF(later)) {
            assertThat(document.getDocumentInformation().getCreationDate().toInstant()).isEqualTo(other);
        }
    }

    @Test
    void theFontIsEmbedded() throws IOException {
        try (PDDocument document = Loader.loadPDF(renderer.render(data(new BigDecimal("150"), List.of("CS-3310")), AT))) {
            List<String> embedded = new ArrayList<>();
            for (PDPage page : document.getPages()) {
                for (COSName name : page.getResources().getFontNames()) {
                    PDFont font = page.getResources().getFont(name);
                    assertThat(font.isEmbedded()).as("font " + font.getName() + " is embedded").isTrue();
                    embedded.add(font.getName());
                }
            }
            assertThat(embedded).isNotEmpty().allSatisfy(name -> assertThat(name).contains("DejaVu"));
        }
    }
}
