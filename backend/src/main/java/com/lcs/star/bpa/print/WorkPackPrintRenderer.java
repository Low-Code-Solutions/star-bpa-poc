package com.lcs.star.bpa.print;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * Renders the Workpack print (BPA-10; 09 S1, S3): Thymeleaf fills the XHTML template {@code print/workpack-print.html}, OpenHTMLtoPDF
 * converts it with the embedded DejaVu Sans, and a PDFBox pass sets the document dates and the document /ID from the instant given
 * (the injected clock's, S9) instead of the wall clock and a random id, so the same data and instant give the same bytes and the same
 * sha256 on a re-run. English only, no Arabic (S3). The tests assert the extracted text, not the layout bytes.
 */
@Component
public class WorkPackPrintRenderer {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.ENGLISH);
    private static final String PRODUCER = "STAR BPA POC";

    private final ZoneId businessZone;
    private final TemplateEngine templates = new TemplateEngine();

    public WorkPackPrintRenderer(ZoneId businessZone) {
        this.businessZone = businessZone;
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("print/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.XML);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.setCacheable(true);
        templates.setTemplateResolver(resolver);
    }

    /** The print as a PDF: the same bytes for the same data and the same instant. */
    public byte[] render(PrintData data, Instant at) {
        Context context = new Context(Locale.ENGLISH);
        context.setVariable("v", view(data, at));
        String html = templates.process("workpack-print", context);
        return stamp(convert(html), html, at);
    }

    private View view(PrintData data, Instant at) {
        return new View(data.workpackNumber(), data.activityRef(), data.drawingRef(), data.departmentRef(), shown(data.completionDate()),
                shown(data.plannedStart()), shown(data.plannedEnd()),
                data.phases().stream().map(p -> new Line(p.phase(), plain(p.manhours()))).toList(), plain(data.totalManhours()),
                data.materials().stream().map(m -> new Line(m.materialRef(), m.phase())).toList(), data.cuttingSheets(),
                "Generated " + STAMP.format(at.atZone(businessZone)) + " (" + businessZone + ")");
    }

    /** Man hours print as entered: 150.50 from numeric(7,2) is 150.5, 400.00 is 400 (BPA-10 "Decimal man hours print as entered"). */
    private static String plain(BigDecimal value) {
        return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private static String shown(LocalDate date) {
        return date == null ? "" : DATE.format(date);
    }

    private static byte[] convert(String html) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.useFont(() -> font("DejaVuSans.ttf"), "DejaVu Sans", 400, BaseRendererBuilder.FontStyle.NORMAL, true);
            builder.useFont(() -> font("DejaVuSans-Bold.ttf"), "DejaVu Sans", 700, BaseRendererBuilder.FontStyle.NORMAL, true);
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("The Workpack print could not be rendered", e);
        } catch (Exception e) {
            throw new IllegalStateException("The Workpack print could not be rendered", e);
        }
    }

    private static InputStream font(String file) {
        InputStream stream = WorkPackPrintRenderer.class.getResourceAsStream("/fonts/" + file);
        if (stream == null) {
            throw new IllegalStateException("Font " + file + " is missing from the classpath");
        }
        return stream;
    }

    /** Document dates from the injected clock's instant, and a document /ID derived from the content and that instant. */
    private static byte[] stamp(byte[] pdf, String html, Instant at) {
        try (PDDocument document = Loader.loadPDF(pdf); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            GregorianCalendar when = new GregorianCalendar(TimeZone.getTimeZone(ZoneOffset.UTC));
            when.setTimeInMillis(at.toEpochMilli());
            PDDocumentInformation info = document.getDocumentInformation();
            info.setCreationDate(when);
            info.setModificationDate(when);
            info.setProducer(PRODUCER);
            info.setCreator(PRODUCER);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((html + "|" + at).getBytes(StandardCharsets.UTF_8));
            COSArray id = new COSArray();
            id.add(new COSString(Arrays.copyOfRange(digest, 0, 16)));
            id.add(new COSString(Arrays.copyOfRange(digest, 16, 32)));
            document.getDocument().setDocumentID(id);
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("The Workpack print could not be stamped", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** What the template shows: ready text only. Thymeleaf reads it by method, e.g. {@code v.workpackNumber()}. */
    public record View(String workpackNumber, String activityRef, String drawingRef, String departmentRef, String completionDate,
            String plannedStart, String plannedEnd, List<Line> phases, String totalManhours, List<Line> materials,
            List<String> cuttingSheets, String generated) {
    }

    public record Line(String label, String value) {
    }
}
