package com.lcs.star.bpa.api;

import com.lcs.star.bpa.api.generated.TimesheetsApi;
import com.lcs.star.bpa.api.generated.model.DigitalTimesheet;
import com.lcs.star.bpa.persistence.DigitalTimesheetRepository;
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
 * {@code GET /api/v1/digital-timesheets?workpackNumber=} (BPA-15): the Work Pack's Digital Time sheets, one per assigned Worker, with
 * employeeRef, workpackNumber, projectRef, wbsRef, activityRef, cbsCodeRef, costCentreRef and submissionStatus (the last two codes are absent
 * when blank). SecurityConfig lets only the supervisor group through (403 otherwise; the group is an ASSUMPTION in the story, 09 S6; no
 * department or project scope, BPA-44). A Work Pack without time sheets yet answers an empty list (ASSUMPTION, as BPA-7 and BPA-14); an unknown
 * one answers 404 "Work Pack not found".
 */
@RestController
public class DigitalTimesheetController implements TimesheetsApi {

    private final DigitalTimesheetRepository timesheets;

    public DigitalTimesheetController(DigitalTimesheetRepository timesheets) {
        this.timesheets = timesheets;
    }

    @Override
    public ResponseEntity<List<DigitalTimesheet>> listDigitalTimesheets(String workpackNumber) {
        return ResponseEntity.ok(timesheets.findByWorkpackNumber(workpackNumber).orElseThrow(() -> new ErrorResponseException(
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
