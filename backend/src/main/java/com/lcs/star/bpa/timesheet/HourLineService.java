package com.lcs.star.bpa.timesheet;

import com.fasterxml.jackson.databind.JsonNode;
import com.lcs.star.bpa.adapter.CamundaDecisionClient;
import com.lcs.star.bpa.domain.HourLineChecks;
import com.lcs.star.bpa.persistence.CbsBlockRepository;
import com.lcs.star.bpa.persistence.WorkerLeaveRepository;
import com.lcs.star.bpa.persistence.DigitalTimesheetLineRepository;
import com.lcs.star.bpa.persistence.HoursSubmissionRepository;
import com.lcs.star.bpa.persistence.DigitalTimesheetLineRepository.Line;
import com.lcs.star.bpa.persistence.DigitalTimesheetLineRepository.Sheet;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Add, change and delete hour lines on a Digital Time sheet (BPA-50; E2-03). Every rule is checked here, one message per refusal and in this order
 * (ASSUMPTION): time sheet or line found, Cancelled, editable status, remarks, hours, decimals, reported completion, future date, cut-off week, phase
 * on the Work Pack, Worker assigned, duplicate, then Decision_HoursWithinBudget at every save (its rule scenarios belong to BPA-51, BPA-69 and
 * BPA-75; cbsClosed is read from cbs_block since BPA-69, workerOnLeave from worker_leave since BPA-75). Only the write runs in a
 * transaction: the line insert, update or delete, its audit rows and the Released to In Progress move (D1). Every check, the sums the decision gets
 * and the decision itself (a call to Camunda) run before it, so two saves at the same moment can both pass the daily and phase limits; only the
 * one-line rule has a database backstop (the partial unique index, mapped to the same 400). A POC accepts that (QA review). Dates and the clock are
 * the business zone's (ADR 0013). A refusal is a {@link Refused}; an engine that cannot answer the decision is an
 * EngineUnavailableException (the controller answers 503 and nothing is saved).
 */
@Service
public class HourLineService {

    static final String DECISION = "Decision_HoursWithinBudget";

    /** A rule refused the call; {@code status} is the HTTP status (400, 404 or 409) and {@code getMessage()} the story's text. */
    public static final class Refused extends RuntimeException {
        private final int status;

        Refused(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    /** A time sheet with its lines and what the grid needs. */
    public record View(Sheet sheet, List<String> phases, LocalDate openWeekStart, LocalDate today, List<Line> lines) {
    }

    private final DigitalTimesheetLineRepository lines;
    private final CbsBlockRepository cbsBlocks;
    private final WorkerLeaveRepository workerLeave;
    private final HoursSubmissionRepository submissions;
    private final CamundaDecisionClient decisions;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final ZoneId businessZone;
    private final BigDecimal dailyHoursLimit;

    public HourLineService(DigitalTimesheetLineRepository lines, CbsBlockRepository cbsBlocks, WorkerLeaveRepository workerLeave, HoursSubmissionRepository submissions, CamundaDecisionClient decisions, TransactionTemplate transaction, Clock clock,
            ZoneId businessZone, @Value("${app.daily-hours-limit}") BigDecimal dailyHoursLimit) {
        this.lines = lines;
        this.cbsBlocks = cbsBlocks;
        this.workerLeave = workerLeave;
        this.submissions = submissions;
        this.decisions = decisions;
        this.transaction = transaction;
        this.clock = clock;
        this.businessZone = businessZone;
        this.dailyHoursLimit = dailyHoursLimit;
    }

    public View view(UUID timesheetId) {
        Sheet sheet = sheet(timesheetId);
        LocalDate today = today();
        return new View(sheet, lines.phases(sheet.workpackId()), HourLineChecks.openWeekStart(today, lines.latestClosedWeekEnding()), today, lines.lines(timesheetId));
    }

    /** The submission with its lines for the Timekeeper's screen (BPA-53); 404 "Submission not found". */
    public HoursSubmissionRepository.Submission submission(UUID id) {
        return submissions.find(id).orElseThrow(() -> new Refused(404, "Submission not found"));
    }

    public Line add(UUID timesheetId, LocalDate date, String phase, BigDecimal hours, String remarks, BigDecimal completion, String username) {
        return add(timesheetId, date, phase, hours, remarks, completion, null, username);
    }

    /**
     * The same save for a row of a time sheet upload (BPA-52): {@code timesheetFileId} is the upload's file, which makes the line's entry source Upload
     * (null is a form entry). Every rule and the decision are exactly the form's, so a row is checked as a form entry is (E2-D4).
     */
    public Line add(UUID timesheetId, LocalDate date, String phase, BigDecimal hours, String remarks, BigDecimal completion, UUID timesheetFileId,
            String username) {
        Sheet sheet = sheet(timesheetId);
        checkWorkPackOpen(sheet); // BPA-67: a Completed Work Pack takes no hours (409, Jira's text), before any rule about the date
        if ("Cancelled".equals(sheet.submissionStatus())) {
            throw new Refused(409, "Time sheet for " + sheet.employeeRef() + " on " + sheet.workpackNumber() + " is Cancelled");
        }
        // BPA-53 (plan Q2): one rule for the form (409) and, through here, for each row of an upload (its outcome text)
        if (submissions.inApproval(sheet.workpackId())) {
            throw new Refused(409, "Hours for " + sheet.workpackNumber() + " are in approval; book them after the decision");
        }
        checkValues(hours, remarks, completion);
        refuse(HourLineChecks.notInTheFuture(date, today()));
        checkMonthOpen(date); // BPA-67 (S5): the month text first when the week is closed too (ASSUMPTION, plan Q9)
        checkWeekOpen(date);
        BigDecimal budget = lines.phaseBudget(sheet.workpackId(), phase)
                .orElseThrow(() -> new Refused(400, "Phase " + phase + " is not on this Work Pack"));
        if (!lines.workerCanBook(sheet.workpackId(), sheet.workerId())) {
            throw new Refused(400, "Worker " + sheet.employeeRef() + " is not assigned to " + sheet.workpackNumber());
        }
        if (lines.duplicate(timesheetId, date, phase)) {
            throw duplicate(sheet, date, phase);
        }
        checkBudget(sheet, date, phase, hours, budget, null);
        Instant now = clock.instant();
        try {
            UUID id = transaction.execute(tx -> {
                UUID saved = lines.insert(timesheetId, date, phase, hours, remarks, completion, timesheetFileId, username, now);
                lines.startWorkPack(sheet.workpackId(), username, now);
                return saved;
            });
            return lines.line(id).orElseThrow();
        } catch (DuplicateKeyException e) {
            // two saves at the same moment: the partial unique index decides (E2-D4)
            throw duplicate(sheet, date, phase);
        }
    }

    public Line change(UUID lineId, BigDecimal hours, String remarks, BigDecimal completion, String username) {
        Line old = line(lineId);
        checkEditable(old);
        checkValues(hours, remarks, completion);
        BigDecimal budget = lines.phaseBudget(old.sheet().workpackId(), old.phase())
                .orElseThrow(() -> new Refused(400, "Phase " + old.phase() + " is not on this Work Pack"));
        checkBudget(old.sheet(), old.workDate(), old.phase(), hours, budget, old.id());
        Instant now = clock.instant();
        transaction.executeWithoutResult(tx -> lines.update(old, hours, remarks, completion, username, now));
        return lines.line(lineId).orElseThrow();
    }

    /**
     * The Timekeeper amends the hours of a sent line on request (BPA-53; FRD-5-12 §5.12.5.5). Only while the line is Submitted and its submission waits
     * at the Timekeeper, because that is when UserTask_ValidateHours is open (ASSUMPTION: the story gives no other state). Checked in order: line found,
     * state (409, the BPA-50 text), reason required and at most 250 characters (ASSUMPTION text), hours, then Decision_HoursWithinBudget with the line's
     * old hours left out (BPA-51). Only hours change; the line stays Submitted. Audit rows: hours, and the reason as column {@code amend_reason}
     * (ASSUMPTION, plan Q1: no column holds it).
     */
    public Line amend(UUID lineId, BigDecimal hours, String reason, String username) {
        Line old = line(lineId);
        if (!"Submitted".equals(old.status()) || !submissions.stageOfLine(lineId).map("Timekeeper"::equals).orElse(false)) {
            throw new Refused(409, "Line is not editable (status " + old.status() + ")");
        }
        checkWorkPackOpen(old.sheet()); // BPA-67
        checkMonthOpen(old.workDate()); // BPA-67
        if (reason == null || reason.isBlank()) {
            throw new Refused(400, "Reason is required");
        }
        if (reason.trim().length() > 250) {
            throw new Refused(400, "Reason can be at most 250 characters");
        }
        refuse(HourLineChecks.hours(hours));
        BigDecimal budget = lines.phaseBudget(old.sheet().workpackId(), old.phase())
                .orElseThrow(() -> new Refused(400, "Phase " + old.phase() + " is not on this Work Pack"));
        checkBudget(old.sheet(), old.workDate(), old.phase(), hours, budget, old.id());
        Instant now = clock.instant();
        transaction.executeWithoutResult(tx -> lines.amend(old, hours, reason.trim(), username, now));
        return lines.line(lineId).orElseThrow();
    }

    public void delete(UUID lineId, String username) {
        Line old = line(lineId);
        checkEditable(old);
        Instant now = clock.instant();
        transaction.executeWithoutResult(tx -> lines.delete(old, username, now));
    }

    // --- rules ------------------------------------------------------------------------------------------------------------

    private void checkValues(BigDecimal hours, String remarks, BigDecimal completion) {
        refuse(HourLineChecks.remarks(remarks));
        refuse(HourLineChecks.hours(hours));
        refuse(HourLineChecks.completion(completion));
    }

    /** Only Draft and Returned lines change (FRD-5-12 §5.12.8.1); a Draft line in a cut-off week does not (E2-D15; a Returned one may be corrected, BPA-67 a). */
    private void checkEditable(Line line) {
        if (!List.of("Draft", "Returned").contains(line.status())) {
            throw new Refused(409, "Line is not editable (status " + line.status() + ")");
        }
        checkWorkPackOpen(line.sheet()); // BPA-67: no change or delete on a Completed Work Pack (ASSUMPTION, plan Q7)
        checkMonthOpen(line.workDate()); // BPA-67 (S5): after the lock no line of the month changes, a Returned one included; before it a Returned line can still be corrected
        if ("Draft".equals(line.status())) {
            checkWeekOpen(line.workDate());
        }
    }

    /** BPA-67: 409 "Work Pack <WP> is Completed". */
    private void checkWorkPackOpen(Sheet sheet) {
        if ("Completed".equals(sheet.workpackStatus())) {
            throw new Refused(409, HourLineChecks.completedMessage(sheet.workpackNumber()));
        }
    }

    /** BPA-67 (S5): 400 "Hours for <Month YYYY> are locked" once BPA-64's month lock has run for the month of the work date. */
    private void checkMonthOpen(LocalDate date) {
        if (lines.monthLocked(date)) {
            throw new Refused(400, HourLineChecks.monthLockedMessage(date));
        }
    }

    private void checkWeekOpen(LocalDate date) {
        LocalDate weekEnding = HourLineChecks.weekEnding(date);
        if (lines.weekClosed(weekEnding)) {
            throw new Refused(400, HourLineChecks.weekClosed(weekEnding));
        }
    }

    /**
     * Decision_HoursWithinBudget with this line counted: the Worker's hours that day over all Work Packs and the Work Pack's hours in the phase,
     * both without the line being changed. cbsClosed is read from cbs_block (BPA-69); workerOnLeave from worker_leave (BPA-75).
     */
    private void checkBudget(Sheet sheet, LocalDate date, String phase, BigDecimal hours, BigDecimal budget, UUID exceptLine) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("cbsClosed", cbsBlocks.closedForProductiveHours(sheet.cbsCodeRef())); // BPA-69: the one shared lookup (CbsBlockRepository)
        variables.put("workerOnLeave", workerLeave.onLeave(sheet.workerId(), date)); // BPA-75: the one shared lookup (WorkerLeaveRepository)
        variables.put("dailyHoursAfter", lines.workerHoursOn(sheet.workerId(), date, exceptLine).add(hours));
        variables.put("dailyHoursLimit", dailyHoursLimit); // app.daily-hours-limit, BPA-51 decision (a)
        variables.put("phaseHoursAfter", lines.phaseHours(sheet.workpackId(), phase, exceptLine).add(hours));
        variables.put("phaseBudgetHours", budget);
        JsonNode answer = decisions.evaluate(DECISION, variables);
        if (!"Blocked".equals(answer.path("budgetResult").asText())) {
            return;
        }
        String reason = answer.path("budgetReason").asText();
        throw new Refused(400, switch (reason) {
            // texts from data model §13.2.3 (marked (A) there), the same builders the send-time worker uses
            case "DAILY_LIMIT_EXCEEDED" -> HourLineChecks.dailyLimitMessage(sheet.employeeRef(), date, dailyHoursLimit);
            case "PHASE_BUDGET_EXCEEDED" -> HourLineChecks.phaseBudgetMessage(phase, budget);
            case "CBS_CLOSED" -> HourLineChecks.cbsClosedMessage(sheet.cbsCodeRef()); // BPA-69, shared with the send-time worker
            case "WORKER_ON_LEAVE" -> HourLineChecks.workerOnLeaveMessage(sheet.employeeRef(), date); // BPA-75, shared with the send-time worker
            default -> "Hours are blocked (" + reason + ")";
        });
    }

    // --- small helpers ----------------------------------------------------------------------------------------------------

    private Sheet sheet(UUID id) {
        return lines.sheet(id).orElseThrow(() -> new Refused(404, "Time sheet not found"));
    }

    private Line line(UUID id) {
        return lines.line(id).orElseThrow(() -> new Refused(404, "Line not found"));
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), businessZone);
    }

    private static void refuse(Optional<String> message) {
        message.ifPresent(m -> {
            throw new Refused(400, m);
        });
    }

    private static Refused duplicate(Sheet sheet, LocalDate date, String phase) {
        return new Refused(400, "Hours already booked for " + sheet.employeeRef() + " on " + HourLineChecks.show(date) + " for phase " + phase);
    }
}
