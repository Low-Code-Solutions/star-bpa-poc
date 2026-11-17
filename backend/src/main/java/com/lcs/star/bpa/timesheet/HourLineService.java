package com.lcs.star.bpa.timesheet;

import com.fasterxml.jackson.databind.JsonNode;
import com.lcs.star.bpa.adapter.CamundaDecisionClient;
import com.lcs.star.bpa.domain.HourLineChecks;
import com.lcs.star.bpa.persistence.DigitalTimesheetLineRepository;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Add, change and delete hour lines on a Digital Time sheet (BPA-50; E2-03). Every rule is checked here, one message per refusal and in this order
 * (ASSUMPTION): time sheet or line found, Cancelled, editable status, remarks, hours, decimals, reported completion, future date, cut-off week, phase
 * on the Work Pack, Worker assigned, duplicate, then Decision_HoursWithinBudget at every save (its rule scenarios belong to BPA-51, BPA-69 and
 * BPA-75; here cbsClosed and workerOnLeave are sent as false until those stories read cbs_block and worker_leave). Only the write runs in a
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
    private final CamundaDecisionClient decisions;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final ZoneId businessZone;

    public HourLineService(DigitalTimesheetLineRepository lines, CamundaDecisionClient decisions, TransactionTemplate transaction, Clock clock,
            ZoneId businessZone) {
        this.lines = lines;
        this.decisions = decisions;
        this.transaction = transaction;
        this.clock = clock;
        this.businessZone = businessZone;
    }

    public View view(UUID timesheetId) {
        Sheet sheet = sheet(timesheetId);
        LocalDate today = today();
        return new View(sheet, lines.phases(sheet.workpackId()), HourLineChecks.openWeekStart(today, lines.latestClosedWeekEnding()), today, lines.lines(timesheetId));
    }

    public Line add(UUID timesheetId, LocalDate date, String phase, BigDecimal hours, String remarks, BigDecimal completion, String username) {
        Sheet sheet = sheet(timesheetId);
        if ("Cancelled".equals(sheet.submissionStatus())) {
            throw new Refused(409, "Time sheet for " + sheet.employeeRef() + " on " + sheet.workpackNumber() + " is Cancelled");
        }
        checkValues(hours, remarks, completion);
        refuse(HourLineChecks.notInTheFuture(date, today()));
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
                UUID saved = lines.insert(timesheetId, date, phase, hours, remarks, completion, username, now);
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
        if ("Draft".equals(line.status())) {
            checkWeekOpen(line.workDate());
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
     * both without the line being changed. cbsClosed and workerOnLeave are false for now (BPA-69, BPA-75).
     */
    private void checkBudget(Sheet sheet, LocalDate date, String phase, BigDecimal hours, BigDecimal budget, UUID exceptLine) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("cbsClosed", false);
        variables.put("workerOnLeave", false);
        variables.put("dailyHoursAfter", lines.workerHoursOn(sheet.workerId(), date, exceptLine).add(hours));
        variables.put("phaseHoursAfter", lines.phaseHours(sheet.workpackId(), phase, exceptLine).add(hours));
        variables.put("phaseBudgetHours", budget);
        JsonNode answer = decisions.evaluate(DECISION, variables);
        if (!"Blocked".equals(answer.path("budgetResult").asText())) {
            return;
        }
        String reason = answer.path("budgetReason").asText();
        throw new Refused(400, switch (reason) {
            // texts from data model §13.2.3 (marked (A) there); 16 is the decision's daily limit (E2-D6, ASSUMPTION)
            case "DAILY_LIMIT_EXCEEDED" -> "Worker " + sheet.employeeRef() + " would exceed 16 hours on " + HourLineChecks.show(date);
            case "PHASE_BUDGET_EXCEEDED" -> "Phase " + phase + " budget of " + HourLineChecks.plain(budget) + " h would be exceeded";
            // CBS_CLOSED and WORKER_ON_LEAVE cannot happen yet; BPA-69 and BPA-75 give their texts
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
