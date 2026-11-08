package com.lcs.star.bpa.api;

import com.lcs.star.bpa.api.generated.HourJournalLinesApi;
import com.lcs.star.bpa.api.generated.model.HourJournalLine;
import com.lcs.star.bpa.persistence.HourJournalLineRepository;
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
 * {@code GET /api/v1/hour-journal-lines?workpackNumber=} (BPA-14, D39): the Work Pack's Project Hour Journal Lines, one per assigned Worker,
 * with employeeRef, role, designation, estimatedHours and status. SecurityConfig lets only ppc-team and supervisor through (403 otherwise;
 * the groups are an ASSUMPTION in the story, 09 S6). A Work Pack without Lines yet answers an empty list (ASSUMPTION, as BPA-7 for the
 * Header); an unknown one answers 404 "Work Pack not found".
 */
@RestController
public class HourJournalLineController implements HourJournalLinesApi {

    private final HourJournalLineRepository lines;

    public HourJournalLineController(HourJournalLineRepository lines) {
        this.lines = lines;
    }

    @Override
    public ResponseEntity<List<HourJournalLine>> listHourJournalLines(String workpackNumber) {
        return ResponseEntity.ok(lines.findByWorkpackNumber(workpackNumber).orElseThrow(() -> new ErrorResponseException(
                HttpStatus.NOT_FOUND, ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Work Pack not found"), null)));
    }

    /** A workpackNumber that is missing or outside WP-###### (generated @NotNull, @Pattern): the contract's InvalidWorkpackNumber. */
    @ExceptionHandler({ConstraintViolationException.class, HandlerMethodValidationException.class,
            MissingServletRequestParameterException.class})
    ResponseEntity<ProblemDetail> invalidWorkpackNumber(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Workpack number must match WP-######"));
    }
}
