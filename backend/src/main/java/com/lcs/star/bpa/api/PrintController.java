package com.lcs.star.bpa.api;

import com.lcs.star.bpa.api.generated.PrintsApi;
import com.lcs.star.bpa.api.generated.model.WorkPackPrintSummary;
import com.lcs.star.bpa.persistence.WorkPackPrintRepository;
import jakarta.validation.ConstraintViolationException;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/**
 * The Workpack print API (BPA-10; 09 S4, S6): {@code GET /api/v1/workpacks/{workpackNumber}/print} serves the stored PDF inline for
 * the browser's viewer, and {@code GET /api/v1/workpack-prints} lists the Work Packs that have one for the portal. SecurityConfig lets
 * only time-method-analyst, production-planner and production-team through (403 otherwise; groups ASSUMPTION in the story).
 */
@RestController
public class PrintController implements PrintsApi {

    private final WorkPackPrintRepository prints;

    public PrintController(WorkPackPrintRepository prints) {
        this.prints = prints;
    }

    @Override
    public ResponseEntity<Resource> getWorkPackPrint(String workpackNumber) {
        if (!prints.workpackExists(workpackNumber)) {
            throw notFound("Work Pack not found");
        }
        byte[] pdf = prints.findFile(workpackNumber).orElseThrow(() -> notFound("Workpack print not available"));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + workpackNumber + ".pdf\"")
                .contentLength(pdf.length)
                .body(new ByteArrayResource(pdf));
    }

    @Override
    public ResponseEntity<List<WorkPackPrintSummary>> listWorkPackPrints() {
        return ResponseEntity.ok(prints.list().stream().map(p -> new WorkPackPrintSummary(p.workpackNumber(), p.drawingRef(),
                p.plannedStartDate(), p.plannedEndDate(), p.generatedAt().atOffset(ZoneOffset.UTC))).toList());
    }

    private static ErrorResponseException notFound(String detail) {
        return new ErrorResponseException(HttpStatus.NOT_FOUND, ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, detail), null);
    }

    /** A workpackNumber outside WP-###### (generated @Pattern): the contract's InvalidWorkpackNumber (x-assumption text). */
    @ExceptionHandler({ConstraintViolationException.class, HandlerMethodValidationException.class})
    ResponseEntity<ProblemDetail> invalidWorkpackNumber(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Workpack number must match WP-######"));
    }
}
