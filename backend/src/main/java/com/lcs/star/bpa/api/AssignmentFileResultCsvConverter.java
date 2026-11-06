package com.lcs.star.bpa.api;

import com.lcs.star.bpa.api.generated.model.AssignmentFileResult;
import com.lcs.star.bpa.api.generated.model.AssignmentFileRow;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Component;

/**
 * Writes an assignment file's result as CSV for the download (BPA-12, BPA-46 Q3 PO decision; x-assumption in the contract): chosen by
 * the Accept header {@code text/csv} on {@code GET /api/v1/assignment-files/{id}/result}. A header row with the AssignmentFileRow field
 * names in the order of the BPA-12 result columns, RFC 4180 quoting, UTF-8. A file with a file-level outcome has one line, that
 * outcome. The attachment's name is the upload's file name without .xlsx, plus "-result.csv". Write-only: a CSV is never read back.
 */
@Component
public class AssignmentFileResultCsvConverter extends AbstractHttpMessageConverter<AssignmentFileResult> {

    static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);
    private static final String HEADER = "rowNumber,workpackNumber,badgeNo,workerName,outcome,wpOutcome";

    public AssignmentFileResultCsvConverter() {
        super(CSV);
    }

    @Override
    protected boolean supports(Class<?> clazz) {
        return AssignmentFileResult.class.isAssignableFrom(clazz);
    }

    @Override
    protected boolean canRead(MediaType mediaType) {
        return false;
    }

    @Override
    protected AssignmentFileResult readInternal(Class<? extends AssignmentFileResult> clazz, HttpInputMessage inputMessage) {
        throw new HttpMessageNotReadableException("A CSV result is never read back", inputMessage);
    }

    @Override
    protected void writeInternal(AssignmentFileResult result, HttpOutputMessage output) throws IOException {
        output.getHeaders().set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + downloadName(result.getFileName()) + "\"");
        output.getBody().write(csv(result).getBytes(StandardCharsets.UTF_8));
    }

    static String downloadName(String fileName) {
        // The browser may send a path: keep only the name, and drop quotes, backslashes, semicolons and control characters (review).
        String name = fileName == null ? "assignment" : fileName.replaceAll(".*[\\\\/]", "");
        String base = name.replaceAll("(?i)\\.xlsx$", "").replaceAll("[\\p{Cntrl}\"\\\\;]", "");
        return (base.isBlank() ? "assignment" : base) + "-result.csv";
    }

    static String csv(AssignmentFileResult result) {
        if (result.getOutcome() != null) {
            return field(result.getOutcome()) + "\r\n";
        }
        StringBuilder out = new StringBuilder(HEADER).append("\r\n");
        for (AssignmentFileRow row : result.getRows()) {
            out.append(List.of(
                    row.getRowNumber() == null ? "" : row.getRowNumber().toString(),
                    nullToEmpty(row.getWorkpackNumber()), nullToEmpty(row.getBadgeNo()), nullToEmpty(row.getWorkerName()),
                    nullToEmpty(row.getOutcome()), nullToEmpty(row.getWpOutcome())).stream()
                    .map(AssignmentFileResultCsvConverter::field).collect(Collectors.joining(","))).append("\r\n");
        }
        return out.toString();
    }

    /** RFC 4180: a value with a comma, a quote or a line break is quoted, and its quotes doubled. */
    private static String field(String value) {
        return value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")
                ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
