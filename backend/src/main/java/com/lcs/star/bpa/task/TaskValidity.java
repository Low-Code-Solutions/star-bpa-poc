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

    /** BPA-53: the Timekeeper's decision on the hours sent for a Work Pack. "Reason is required" is the story's text; the unknown-decision text is an ASSUMPTION. */
    static final String VALIDATE_HOURS = "UserTask_ValidateHours";
    static final String HOURS_DECISION_UNKNOWN = "Decision must be Validated or Returned";

    /** BPA-56: the Production Supervisor's decision; the unknown-decision text is an ASSUMPTION, "Reason is required" the story's (Returned and Rejected). */
    static final String APPROVE_PRODUCTION = "UserTask_ApproveHoursProduction";
    static final String APPROVE_PPC = "UserTask_ApproveHoursPpc"; // BPA-57: the same decision and reason rules
    static final String PRODUCTION_DECISION_UNKNOWN = "Decision must be Approved, Returned or Rejected";

    /** BPA-58: PPC's outcome for each FMS file. "Reason is required" and the limit of 250 are the story's; the other texts are ASSUMPTIONs. */
    static final String SEND_HOURS_TO_FMS = "UserTask_SendHoursToFms";
    static final String OUTCOME_REQUIRED = "Outcome is required for each file";
    static final String OUTCOME_UNKNOWN = "Outcome must be Sent to FMS or Rejected by FMS";
    static final String REASON_TOO_LONG = "Reason can be at most 250 characters";
    static final int REASON_MAX = 250;

    /** BPA-60: completing the approval needs the approvals of the week; "Approvals are required" is an ASSUMPTION text. */
    static final String APPROVE_COMPLETION = "UserTask_ApproveCompletion";
    static final String APPROVALS_REQUIRED = "Approvals are required";

    /** BPA-59: the Upload completion report completion needs the report that counts; "Completion report is required" is an ASSUMPTION text. */
    static final String UPLOAD_COMPLETION_REPORT = "UserTask_UploadCompletionReport";
    static final String REPORT_REQUIRED = "Completion report is required";

    private TaskValidity() {
    }

    /** The message to answer with (400), or empty when the completion is valid for this task. */
    public static Optional<String> check(String elementId, Map<String, Object> submitted) {
        if (APPROVE_PRODUCTION.equals(elementId) || APPROVE_PPC.equals(elementId)) {
            return checkProductionDecision(submitted);
        }
        if (APPROVE_COMPLETION.equals(elementId)) {
            return submitted.get("completionApprovals") instanceof java.util.List<?> list && !list.isEmpty() ? Optional.empty() : Optional.of(APPROVALS_REQUIRED);
        }
        if (UPLOAD_COMPLETION_REPORT.equals(elementId)) {
            return submitted.get("completionReportId") instanceof String id && !id.isBlank() ? Optional.empty() : Optional.of(REPORT_REQUIRED);
        }
        if (SEND_HOURS_TO_FMS.equals(elementId)) {
            return checkFmsHandOffs(submitted);
        }
        if (VALIDATE_HOURS.equals(elementId)) {
            return checkHoursDecision(submitted);
        }
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

    /** One entry per file: an outcome that is one of the two, and for a rejection a reason of 1 to 250 characters. Which files the task has is {@link FmsHandOffCheck}'s. */
    private static Optional<String> checkFmsHandOffs(Map<String, Object> submitted) {
        if (!(submitted.get("fmsHandOffs") instanceof java.util.List<?> entries) || entries.isEmpty()) {
            return Optional.of(OUTCOME_REQUIRED);
        }
        for (Object entry : entries) {
            Object outcome = entry instanceof Map<?, ?> map ? map.get("outcome") : null;
            if (outcome == null || (outcome instanceof String text && text.isBlank())) {
                return Optional.of(OUTCOME_REQUIRED);
            }
            if (!"Sent to FMS".equals(outcome) && !"Rejected by FMS".equals(outcome)) {
                return Optional.of(OUTCOME_UNKNOWN);
            }
            if ("Rejected by FMS".equals(outcome)) {
                Object reason = ((Map<?, ?>) entry).get("reason");
                if (!(reason instanceof String text && !text.isBlank())) {
                    return Optional.of(REASON_REQUIRED);
                }
                if (text.trim().length() > REASON_MAX) {
                    return Optional.of(REASON_TOO_LONG);
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> checkHoursDecision(Map<String, Object> submitted) {
        Object decision = submitted.get("hoursDecision");
        if (decision == null || (decision instanceof String text && text.isBlank())) {
            return Optional.of(DECISION_REQUIRED);
        }
        if (!"Validated".equals(decision) && !"Returned".equals(decision)) {
            return Optional.of(HOURS_DECISION_UNKNOWN);
        }
        if ("Returned".equals(decision) && !(submitted.get("hoursReason") instanceof String reason && !reason.isBlank())) {
            return Optional.of(REASON_REQUIRED);
        }
        return Optional.empty();
    }

    private static Optional<String> checkProductionDecision(Map<String, Object> submitted) {
        Object decision = submitted.get("hoursDecision");
        if (decision == null || (decision instanceof String text && text.isBlank())) {
            return Optional.of(DECISION_REQUIRED);
        }
        if (!"Approved".equals(decision) && !"Returned".equals(decision) && !"Rejected".equals(decision)) {
            return Optional.of(PRODUCTION_DECISION_UNKNOWN);
        }
        if (!"Approved".equals(decision) && !(submitted.get("hoursReason") instanceof String reason && !reason.isBlank())) {
            return Optional.of(REASON_REQUIRED);
        }
        return Optional.empty();
    }
}
