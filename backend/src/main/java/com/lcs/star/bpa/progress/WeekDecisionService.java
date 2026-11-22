package com.lcs.star.bpa.progress;

import com.lcs.star.bpa.persistence.ProgressPeriodRepository;
import com.lcs.star.bpa.persistence.ProgressPeriodRepository.State;
import com.lcs.star.bpa.persistence.ProgressWeekRepository;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Project Manager's decision on a week (BPA-66; E2-19; FRD-21 §21.7, §21.8.1), recorded by {@code ServiceTask_RecordWeekDecision} after
 * {@code UserTask_ApproveWeeklyProgress}. In one transaction, with the period row locked (so the lock of BPA-67 never interleaves):
 * <ul>
 * <li><b>Approved:</b> {@code week_approved_by} = {@code completedBy} and {@code week_approved_at} = now; the same approver again writes nothing and keeps the first time.
 * Segregation (the backstop of the task API's 409): the approver must not be a user who approved the % of a Work Pack of the week.</li>
 * <li><b>ChangesRequested:</b> {@code week_decision_reason} = the reason (the latest one; each change is an {@code audit_log} row with who and when, plan Q4).
 * A target (Progress or CostReview) and a reason are required.</li>
 * </ul>
 * A Locked week and a decision the checks refuse throw {@link IllegalArgumentException} (the job fails with no retries, ADR 0006).
 */
@Service
public class WeekDecisionService {

    /** The story's text. */
    public static final String SEGREGATION = "You cannot approve a week whose progress you approved";

    /** The story's text. */
    public static final String REASON_REQUIRED = "Reason is required";

    /** ASSUMPTION texts (plan Q8). */
    public static final String DECISION_UNKNOWN = "Decision must be Approved or ChangesRequested";
    public static final String TARGET_REQUIRED = "Target is required";
    public static final String NO_PERIOD = "The week of this instance has no progress period";

    private final ProgressPeriodRepository periods;
    private final ProgressWeekRepository weeks;

    public WeekDecisionService(ProgressPeriodRepository periods, ProgressWeekRepository weeks) {
        this.periods = periods;
        this.weeks = weeks;
    }

    @Transactional
    public void record(UUID periodId, String decision, String target, String reason, String completedBy) {
        State state = periods.lockState(periodId).orElseThrow(() -> new IllegalArgumentException(NO_PERIOD));
        if ("Locked".equals(state.status())) {
            throw new IllegalArgumentException("Week ending " + state.weekEnding().format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)) + " is locked");
        }
        if ("Approved".equals(decision)) {
            if (weeks.approvedBy(periodId, completedBy)) {
                throw new IllegalArgumentException(SEGREGATION);
            }
            periods.recordApproval(state, completedBy);
        } else if ("ChangesRequested".equals(decision)) {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException(REASON_REQUIRED);
            }
            if (target == null || target.isBlank()) {
                throw new IllegalArgumentException(TARGET_REQUIRED);
            }
            if (!"Progress".equals(target) && !"CostReview".equals(target)) {
                throw new IllegalArgumentException("Target " + target + " is not allowed");
            }
            periods.recordReason(state, reason.trim(), completedBy);
        } else {
            throw new IllegalArgumentException(decision == null || decision.isBlank() ? "Decision is required" : DECISION_UNKNOWN);
        }
    }
}
