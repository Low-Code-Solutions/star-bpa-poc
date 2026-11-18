package com.lcs.star.bpa.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * BPA-9: the task API's own validity check (09 S14), the backend half of "the screen checks it and the backend re-checks it".
 * Not Gherkin scenarios themselves; BPA9_declineNeedsReason (SignOffApiTest) proves the same rule through POST complete.
 * The two decision texts are ASSUMPTIONs (the story states only "Reason is required").
 */
class TaskValidityTest {

    static final String SIGN_OFF = "UserTask_SignOffRelease";

    @Test
    void signOffNeedsADecision() {
        assertThat(TaskValidity.check(SIGN_OFF, Map.of())).contains("Decision is required");
        assertThat(TaskValidity.check(SIGN_OFF, Map.of("declineReason", "Wrong drawing revision"))).contains("Decision is required");
    }

    @Test
    void signOffDecisionMustBeApprovedOrDeclined() {
        assertThat(TaskValidity.check(SIGN_OFF, Map.of("signOffDecision", "Maybe"))).contains("Decision must be Approved or Declined");
        assertThat(TaskValidity.check(SIGN_OFF, Map.of("signOffDecision", "approved"))).contains("Decision must be Approved or Declined");
    }

    @Test
    void declineNeedsAReasonAndBlanksAreNotAReason() {
        assertThat(TaskValidity.check(SIGN_OFF, Map.of("signOffDecision", "Declined"))).contains("Reason is required");
        assertThat(TaskValidity.check(SIGN_OFF, Map.of("signOffDecision", "Declined", "declineReason", ""))).contains("Reason is required");
        assertThat(TaskValidity.check(SIGN_OFF, Map.of("signOffDecision", "Declined", "declineReason", "  \t "))).contains("Reason is required");
    }


    /** BPA-53: the Timekeeper's decision; "Reason is required" is the story's text, the unknown-decision text is an ASSUMPTION. */
    @Test
    void validateHoursChecksDecisionAndReason() {
        String validate = "UserTask_ValidateHours";
        assertThat(TaskValidity.check(validate, Map.of())).contains("Decision is required");
        assertThat(TaskValidity.check(validate, Map.of("hoursDecision", "Rejected"))).contains("Decision must be Validated or Returned");
        assertThat(TaskValidity.check(validate, Map.of("hoursDecision", "Returned"))).contains("Reason is required");
        assertThat(TaskValidity.check(validate, Map.of("hoursDecision", "Returned", "hoursReason", "  "))).contains("Reason is required");
        assertThat(TaskValidity.check(validate, Map.of("hoursDecision", "Returned", "hoursReason", "Wrong phase"))).isEqualTo(Optional.empty());
        assertThat(TaskValidity.check(validate, Map.of("hoursDecision", "Validated"))).isEqualTo(Optional.empty());
    }

    /** BPA-56: the Production Supervisor's decision; "Reason is required" (Returned, Rejected) is the story's text, the unknown-decision text an ASSUMPTION. */
    @Test
    void approveHoursProductionChecksDecisionAndReason() {
        String production = "UserTask_ApproveHoursProduction";
        assertThat(TaskValidity.check(production, Map.of())).contains("Decision is required");
        assertThat(TaskValidity.check(production, Map.of("hoursDecision", "Validated"))).contains("Decision must be Approved, Returned or Rejected");
        assertThat(TaskValidity.check(production, Map.of("hoursDecision", "Returned", "hoursReason", " "))).contains("Reason is required");
        assertThat(TaskValidity.check(production, Map.of("hoursDecision", "Rejected"))).contains("Reason is required");
        assertThat(TaskValidity.check(production, Map.of("hoursDecision", "Rejected", "hoursReason", "Not worked"))).isEqualTo(Optional.empty());
        assertThat(TaskValidity.check(production, Map.of("hoursDecision", "Approved"))).isEqualTo(Optional.empty());
    }

    /** BPA-57: the PPC decision follows the Production rules ("Reason is required" unless Approved). */
    @Test
    void approveHoursPpcChecksDecisionAndReason() {
        String ppc = "UserTask_ApproveHoursPpc";
        assertThat(TaskValidity.check(ppc, Map.of())).contains("Decision is required");
        assertThat(TaskValidity.check(ppc, Map.of("hoursDecision", "Validated"))).contains("Decision must be Approved, Returned or Rejected");
        assertThat(TaskValidity.check(ppc, Map.of("hoursDecision", "Returned"))).contains("Reason is required");
        assertThat(TaskValidity.check(ppc, Map.of("hoursDecision", "Rejected", "hoursReason", " "))).contains("Reason is required");
        assertThat(TaskValidity.check(ppc, Map.of("hoursDecision", "Rejected", "hoursReason", "Duplicate of WP-000461"))).isEqualTo(Optional.empty());
        assertThat(TaskValidity.check(ppc, Map.of("hoursDecision", "Approved"))).isEqualTo(Optional.empty());
    }

    @Test
    void validCompletionsAndOtherTasksPass() {
        assertThat(TaskValidity.check(SIGN_OFF, Map.of("signOffDecision", "Approved"))).isEqualTo(Optional.empty());
        // An Approved completion that also carries a reason is accepted (ASSUMPTION); the worker stores no reason for it.
        assertThat(TaskValidity.check(SIGN_OFF, Map.of("signOffDecision", "Approved", "declineReason", "ignored"))).isEmpty();
        assertThat(TaskValidity.check(SIGN_OFF, Map.of("signOffDecision", "Declined", "declineReason", "Wrong drawing revision"))).isEmpty();
        // No other task has a rule in this story, so an empty completion is not this check's business.
        assertThat(TaskValidity.check("UserTask_ConfirmWorkPack", Map.of())).isEmpty();
        assertThat(TaskValidity.check("UserTask_CreateWorkPack", Map.of())).isEmpty();
    }
}
