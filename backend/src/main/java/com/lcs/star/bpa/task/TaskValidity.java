package com.lcs.star.bpa.task;

import java.util.Map;
import java.util.Optional;

/**
 * The task API's own validity checks (09 S14): rules the React screen also checks, and that affect validity, so the backend
 * re-checks them before it completes the task (BPA-9: Sign off release). Only the first failing message is returned; a task with
 * no rule here has none (the save workers own the Work Pack rules, ADR 0006). Pure, so a unit test covers it.
 *
 * <p>"Reason is required" is the story's text. The two decision texts are ASSUMPTIONs: the gateway after the sign-off falls to
 * Declined by default, so a completion without a decision would otherwise decline without a reason.
 */
public final class TaskValidity {

    static final String SIGN_OFF = "UserTask_SignOffRelease";
    static final String DECISION_REQUIRED = "Decision is required";
    static final String DECISION_UNKNOWN = "Decision must be Approved or Declined";
    static final String REASON_REQUIRED = "Reason is required";

    private TaskValidity() {
    }

    /** The message to answer with (400), or empty when the completion is valid for this task. */
    public static Optional<String> check(String elementId, Map<String, Object> submitted) {
        if (!SIGN_OFF.equals(elementId)) {
            return Optional.empty();
        }
        Object decision = submitted.get("signOffDecision");
        if (decision == null || (decision instanceof String text && text.isBlank())) {
            return Optional.of(DECISION_REQUIRED);
        }
        if (!"Approved".equals(decision) && !"Declined".equals(decision)) {
            return Optional.of(DECISION_UNKNOWN);
        }
        if ("Declined".equals(decision) && !(submitted.get("declineReason") instanceof String reason && !reason.isBlank())) {
            return Optional.of(REASON_REQUIRED);
        }
        return Optional.empty();
    }
}
