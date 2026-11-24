package com.lcs.star.bpa.progress;

import com.lcs.star.bpa.adapter.CamundaProcessClient;
import com.lcs.star.bpa.config.ProgressSettings;
import com.lcs.star.bpa.persistence.ProgressPeriodRepository;
import com.lcs.star.bpa.persistence.ProgressPeriodRepository.Period;
import com.lcs.star.bpa.persistence.ProgressPeriodRepository.State;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.ErrorResponseException;

/**
 * Reopens a Locked week (BPA-83; FRD-21 §21.7, FRD-10 §10.10), for the Project Manager only (SecurityConfig), and only when {@code bpa.progress.allow-reopen} is true (C-E3-3;
 * default false). The order of the checks: the setting (409 "Reopening a locked week is not allowed"), the week (404 "Week not found"), the reason (400 "Enter a reason to reopen
 * the week"; trimmed, not blank), the status (it must be Locked: 409 "Week ending 19 Nov 2026 is not locked", ASSUMPTION text). Then, in one transaction with the period row held:
 * the message {@code WeekReopened} starts a new instance of Process_PPC_WeeklyProgressPeriod at Gateway_MergeApproval (correlation key the week ending: one instance per week), and the
 * period becomes Reopened, linked to that instance, with the reason, who and when, and the earlier week approval cleared. The week then goes through the normal approval and is Locked
 * again by BPA-67's lock step. If Camunda cannot be reached nothing is written (the caller answers 503). If the database write failed after Camunda started the instance, an instance would
 * exist for a week that is still Locked: a POC risk, logged in the plan, no compensation built. Certificate behaviour (Epic 3) is not part of this call.
 */
@Service
public class ProgressReopenService {

    public static final String NOT_ALLOWED = "Reopening a locked week is not allowed";
    public static final String REASON_REQUIRED = "Enter a reason to reopen the week";
    public static final String MESSAGE = "WeekReopened";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final ProgressSettings settings;
    private final ProgressPeriodRepository periods;
    private final CamundaProcessClient camunda;

    public ProgressReopenService(ProgressSettings settings, ProgressPeriodRepository periods, CamundaProcessClient camunda) {
        this.settings = settings;
        this.periods = periods;
        this.camunda = camunda;
    }

    /** The week after the reopen. */
    public record Reopened(LocalDate weekEnding, String reopenedBy, Timestamp reopenedAt, String reason) {
    }

    /** Throws an {@link ErrorResponseException} for a refusal (400, 404, 409) and {@code EngineUnavailableException} when Camunda cannot be reached; nothing is written then. */
    @Transactional
    public Reopened reopen(LocalDate weekEnding, String reason, String username) {
        if (!settings.allowReopen()) {
            throw refused(HttpStatus.CONFLICT, NOT_ALLOWED);
        }
        Period period = periods.ofWeek(weekEnding).orElseThrow(() -> refused(HttpStatus.NOT_FOUND, "Week not found"));
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.isEmpty()) {
            throw refused(HttpStatus.BAD_REQUEST, REASON_REQUIRED);
        }
        State state = periods.lockState(period.id()).orElseThrow(() -> refused(HttpStatus.NOT_FOUND, "Week not found"));
        if (!"Locked".equals(state.status())) {
            throw refused(HttpStatus.CONFLICT, "Week ending " + state.weekEnding().format(DATE) + " is not locked");
        }
        OptionalLong started = camunda.correlateForKey(MESSAGE, weekEnding.toString(),
                Map.of("progressPeriodId", period.id().toString(), "weekEnding", weekEnding.toString()));
        if (started.isEmpty()) {
            // ASSUMPTION (text): an instance of this week is already active for the message, so another cannot start
            throw refused(HttpStatus.CONFLICT, "Week ending " + state.weekEnding().format(DATE) + " is already being approved");
        }
        Timestamp at = periods.reopen(state, trimmed, started.getAsLong(), username);
        return new Reopened(weekEnding, username, at, trimmed);
    }

    private static ErrorResponseException refused(HttpStatus status, String detail) {
        return new ErrorResponseException(status, ProblemDetail.forStatusAndDetail(status, detail), null);
    }
}
