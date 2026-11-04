package com.lcs.star.bpa.api;

import com.lcs.star.bpa.api.generated.HourJournalHeadersApi;
import com.lcs.star.bpa.api.generated.model.HourJournalHeader;
import com.lcs.star.bpa.persistence.HourJournalHeaderRepository;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/**
 * {@code GET /api/v1/hour-journal-headers?workpackNumber=} (BPA-7, D22): the Work Pack's Project Hour Journal Header as a list
 * of one. SecurityConfig lets only ppc-team and time-method-analyst through (403 otherwise; groups ASSUMPTION in BPA-7).
 */
@RestController
public class HourJournalHeaderController implements HourJournalHeadersApi {

    private final HourJournalHeaderRepository headers;

    public HourJournalHeaderController(HourJournalHeaderRepository headers) {
        this.headers = headers;
    }

    @Override
    public ResponseEntity<List<HourJournalHeader>> listHourJournalHeaders(String workpackNumber) {
        // An empty list while the header step has not run yet (ASSUMPTION; the contract says one once the Work Pack is saved).
        return ResponseEntity.ok(headers.findByWorkpackNumber(workpackNumber).orElseThrow(() -> new ErrorResponseException(
                HttpStatus.NOT_FOUND, ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Work Pack not found"), null)));
    }

    /**
     * A workpackNumber that is missing or outside WP-###### (generated @NotNull, @Pattern): the contract's InvalidWorkpackNumber
     * ("missing or does not match", x-assumption text).
     */
    @ExceptionHandler({ConstraintViolationException.class, HandlerMethodValidationException.class,
            MissingServletRequestParameterException.class})
    ResponseEntity<ProblemDetail> invalidWorkpackNumber(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Workpack number must match WP-######"));
    }
}
