package com.lcs.star.bpa.api;

import com.lcs.star.bpa.adapter.CamundaTaskClient.EngineUnavailableException;
import com.lcs.star.bpa.api.generated.TimesheetProcessesApi;
import com.lcs.star.bpa.api.generated.model.TimesheetProcessRequest;
import com.lcs.star.bpa.timesheet.TimesheetProcessStarter;
import com.lcs.star.bpa.timesheet.TimesheetProcessStarter.Outcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/timesheet-processes}: the one-off start of a Work Pack's timesheet process, for Work Packs assigned before Epic 2 was
 * deployed (BPA-49; group ppc-team, ASSUMPTION; rule in SecurityConfig). 201 started; 409 "Timesheet process already running for WP-…" or "WP-…
 * is not assigned yet"; 404 "Work Pack not found"; 400 for a missing or malformed number; 503 when the engine cannot take the message (nothing is
 * recorded then, so the call can be repeated). The work is in {@link TimesheetProcessStarter}.
 */
@RestController
public class TimesheetProcessController implements TimesheetProcessesApi {

    private static final Logger LOG = LoggerFactory.getLogger(TimesheetProcessController.class);

    static final String REQUIRED = "workpackNumber is required, as WP- followed by 6 digits";

    private final TimesheetProcessStarter starter;

    public TimesheetProcessController(TimesheetProcessStarter starter) {
        this.starter = starter;
    }

    @Override
    public ResponseEntity<TimesheetProcessRequest> startTimesheetProcess(TimesheetProcessRequest request) {
        String number = request.getWorkpackNumber();
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        Outcome outcome;
        try {
            outcome = starter.start(number, username);
        } catch (EngineUnavailableException e) {
            LOG.warn("Timesheet process of {} not started: {}", number, e.getMessage());
            throw problem(HttpStatus.SERVICE_UNAVAILABLE, "The process engine is not available right now; try again later");
        }
        return switch (outcome) {
            case STARTED -> ResponseEntity.status(HttpStatus.CREATED).body(request);
            case ALREADY_RUNNING -> throw problem(HttpStatus.CONFLICT, "Timesheet process already running for " + number);
            case NOT_ASSIGNED -> throw problem(HttpStatus.CONFLICT, number + " is not assigned yet");
            case WORK_PACK_NOT_FOUND -> throw problem(HttpStatus.NOT_FOUND, "Work Pack not found");
        };
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class})
    ResponseEntity<ProblemDetail> invalid(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, REQUIRED));
    }

    private static ErrorResponseException problem(HttpStatus status, String detail) {
        return new ErrorResponseException(status, ProblemDetail.forStatusAndDetail(status, detail), null);
    }
}
