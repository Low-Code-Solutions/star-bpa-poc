package com.lcs.star.bpa.api;

import com.lcs.star.bpa.api.generated.LookAheadApi;
import com.lcs.star.bpa.api.generated.model.LookAhead;
import com.lcs.star.bpa.persistence.LookAheadRepository;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/**
 * {@code GET /api/v1/workpacks/{workpackNumber}/look-ahead} (BPA-11, PO decision 2 Nov 2026 on BPA-46 Q4; D48; 09 S20): the three
 * Look-Ahead PPC Plan-Dates of the Work Pack's drawing, for the Schedule Work Pack screen. A drawing with no Look-Ahead row gets 200
 * with the dates left out (the contract's x-assumption); an unknown Work Pack gets 404. Open to every logged-in user
 * (SecurityConfig: anyRequest authenticated).
 */
@RestController
public class LookAheadController implements LookAheadApi {

    private final LookAheadRepository lookAhead;

    public LookAheadController(LookAheadRepository lookAhead) {
        this.lookAhead = lookAhead;
    }

    @Override
    public ResponseEntity<LookAhead> getLookAhead(String workpackNumber) {
        var found = lookAhead.findByWorkpackNumber(workpackNumber).orElseThrow(() -> new ErrorResponseException(
                HttpStatus.NOT_FOUND, ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Work Pack not found"), null));
        // The dates that are null stay null, and Jackson leaves them out (default-property-inclusion: non_null).
        return ResponseEntity.ok(new LookAhead(found.workpackNumber(), found.drawingRef())
                .shopDrgMarkPlanDate(found.shopDrgMarkPlanDate())
                .assemblyPlanDate(found.assemblyPlanDate())
                .weldMapPlanDate(found.weldMapPlanDate()));
    }

    /** A workpackNumber outside WP-###### (generated @Pattern): the contract's InvalidWorkpackNumber (x-assumption text). */
    @ExceptionHandler({ConstraintViolationException.class, HandlerMethodValidationException.class})
    ResponseEntity<ProblemDetail> invalidWorkpackNumber(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Workpack number must match WP-######"));
    }
}
