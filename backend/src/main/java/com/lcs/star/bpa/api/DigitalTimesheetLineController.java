package com.lcs.star.bpa.api;

import com.lcs.star.bpa.adapter.CamundaTaskClient.EngineUnavailableException;
import com.lcs.star.bpa.api.generated.TimesheetLinesApi;
import com.lcs.star.bpa.api.generated.model.DigitalTimesheetLine;
import com.lcs.star.bpa.api.generated.model.DigitalTimesheetLineChange;
import com.lcs.star.bpa.api.generated.model.HoursSubmission;
import com.lcs.star.bpa.api.generated.model.HoursSubmissionLine;
import com.lcs.star.bpa.api.generated.model.LineAmendment;
import com.lcs.star.bpa.api.generated.model.NewDigitalTimesheetLine;
import com.lcs.star.bpa.api.generated.model.TimesheetLines;
import com.lcs.star.bpa.api.generated.model.TimesheetPhase;
import com.lcs.star.bpa.persistence.DigitalTimesheetLineRepository.Line;
import com.lcs.star.bpa.timesheet.HourLineService;
import com.lcs.star.bpa.timesheet.HourLineService.Refused;
import com.lcs.star.bpa.timesheet.HourLineService.View;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hour lines on Digital Time sheets (BPA-50; E2-03): {@code GET} and {@code POST /api/v1/digital-timesheets/{id}/lines}, {@code PUT} and
 * {@code DELETE /api/v1/digital-timesheet-lines/{id}}, and (BPA-53) {@code POST .../{id}/amendments} and {@code GET /api/v1/hours-submissions/{id}} for the
 * timekeeper group. SecurityConfig lets the supervisor and production-team groups through (C-E2-5; 403
 * otherwise). Every rule and its message is in {@link HourLineService}; a refusal becomes the problem the story names (400, 404 or 409), and an
 * engine that cannot evaluate Decision_HoursWithinBudget becomes 503 with nothing saved.
 */
@RestController
public class DigitalTimesheetLineController implements TimesheetLinesApi {

    private static final Logger LOG = LoggerFactory.getLogger(DigitalTimesheetLineController.class);

    static final String REQUIRED = "workDate, phase and hours are required";

    private final HourLineService service;

    public DigitalTimesheetLineController(HourLineService service) {
        this.service = service;
    }

    @Override
    public ResponseEntity<TimesheetLines> listTimesheetLines(UUID timesheetId) {
        View view = service.view(timesheetId);
        TimesheetLines out = new TimesheetLines();
        out.setTimesheetId(view.sheet().id());
        out.setEmployeeRef(view.sheet().employeeRef());
        out.setWorkpackNumber(view.sheet().workpackNumber());
        out.setSubmissionStatus(TimesheetLines.SubmissionStatusEnum.fromValue(view.sheet().submissionStatus()));
        out.setPhases(view.phases().stream().map(TimesheetPhase::fromValue).toList());
        out.setOpenWeekStart(view.openWeekStart());
        out.setToday(view.today());
        out.setLines(view.lines().stream().map(DigitalTimesheetLineController::map).toList());
        return ResponseEntity.ok(out);
    }

    @Override
    public ResponseEntity<DigitalTimesheetLine> addTimesheetLine(UUID timesheetId, NewDigitalTimesheetLine body) {
        Line saved = service.add(timesheetId, body.getWorkDate(), body.getPhase().getValue(), body.getHours(), body.getRemarks(),
                body.getReportedCompletionPct(), username());
        return ResponseEntity.status(HttpStatus.CREATED).body(map(saved));
    }

    @Override
    public ResponseEntity<DigitalTimesheetLine> updateTimesheetLine(UUID lineId, DigitalTimesheetLineChange body) {
        return ResponseEntity.ok(map(service.change(lineId, body.getHours(), body.getRemarks(), body.getReportedCompletionPct(), username())));
    }

    @Override
    public ResponseEntity<Void> deleteTimesheetLine(UUID lineId) {
        service.delete(lineId, username());
        return ResponseEntity.noContent().build();
    }

    /** BPA-53: the Timekeeper amends the hours of a sent line; SecurityConfig lets only the timekeeper group through. */
    @Override
    public ResponseEntity<DigitalTimesheetLine> amendTimesheetLine(UUID lineId, LineAmendment body) {
        return ResponseEntity.ok(map(service.amend(lineId, body.getHours(), body.getReason(), username())));
    }

    /** BPA-53: a submission with its lines, for the Timekeeper's screen. */
    @Override
    public ResponseEntity<HoursSubmission> getHoursSubmission(UUID submissionId) {
        var submission = service.submission(submissionId);
        HoursSubmission out = new HoursSubmission();
        out.setId(submission.id());
        out.setWorkpackNumber(submission.workpackNumber());
        out.setRoundNo(submission.roundNo());
        out.setStage(HoursSubmission.StageEnum.fromValue(submission.stage()));
        out.setSubmittedBy(submission.submittedBy());
        out.setSubmittedAt(submission.submittedAt().atOffset(java.time.ZoneOffset.UTC));
        out.setLines(submission.lines().stream().map(line -> {
            HoursSubmissionLine l = new HoursSubmissionLine();
            l.setId(line.id());
            l.setBadgeNo(line.badgeNo());
            l.setWorkDate(line.workDate());
            l.setPhase(TimesheetPhase.fromValue(line.phase()));
            l.setHours(line.hours());
            l.setStatus(HoursSubmissionLine.StatusEnum.fromValue(line.status()));
            l.setRemarks(line.remarks());
            return l;
        }).toList());
        return ResponseEntity.ok(out);
    }

    // --- problems ---------------------------------------------------------------------------------------------------------

    @ExceptionHandler(Refused.class)
    ResponseEntity<ProblemDetail> refused(Refused e) {
        return problem(HttpStatus.valueOf(e.status()), e.getMessage());
    }

    @ExceptionHandler(EngineUnavailableException.class)
    ResponseEntity<ProblemDetail> engineUnavailable(EngineUnavailableException e) {
        LOG.warn("Hour line not saved: {}", e.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "The process engine is not available right now; try again later");
    }

    /** A missing workDate, phase or hours, or a phase outside the four (ASSUMPTION: the story gives no text for them). */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class})
    ResponseEntity<ProblemDetail> unreadable(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, REQUIRED);
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(ProblemDetail.forStatusAndDetail(status, detail));
    }

    private static String username() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    private static DigitalTimesheetLine map(Line line) {
        DigitalTimesheetLine out = new DigitalTimesheetLine();
        out.setId(line.id());
        out.setWorkDate(line.workDate());
        out.setPhase(TimesheetPhase.fromValue(line.phase()));
        out.setHours(line.hours());
        out.setStatus(DigitalTimesheetLine.StatusEnum.fromValue(line.status()));
        out.setEntrySource(DigitalTimesheetLine.EntrySourceEnum.fromValue(line.entrySource()));
        out.setRemarks(line.remarks());
        out.setReportedCompletionPct(line.reportedCompletionPct());
        out.setCreatedBy(line.createdBy());
        return out;
    }
}
