package com.lcs.star.bpa.api;

import com.lcs.star.bpa.api.generated.FmsHoursExportsApi;
import com.lcs.star.bpa.persistence.FmsHoursExportRepository;
import com.lcs.star.bpa.persistence.FmsHoursExportRepository.StoredFile;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.RestController;

/**
 * The weekly FMS hours file for download (BPA-58; E2-08): {@code GET /api/v1/fms-hours-exports/{id}/file}. SecurityConfig lets only ppc-team through (403
 * otherwise, agreed 10 Nov 2026 (a)). The stored bytes are returned every time; the file is never rebuilt (agreed (d)). The file name is an ASSUMPTION.
 */
@RestController
public class FmsHoursExportController implements FmsHoursExportsApi {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final FmsHoursExportRepository exports;

    public FmsHoursExportController(FmsHoursExportRepository exports) {
        this.exports = exports;
    }

    @Override
    public ResponseEntity<Resource> downloadFmsHoursExport(UUID fmsHoursExportId) {
        StoredFile stored = exports.file(fmsHoursExportId).orElseThrow(() -> new ErrorResponseException(
                HttpStatus.NOT_FOUND, ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "File not found"), null));
        String name = "fms-hours-" + stored.weekEnding() + "-" + stored.periodMonth().format(MONTH) + ".xlsx";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .body(new ByteArrayResource(stored.file()));
    }
}
