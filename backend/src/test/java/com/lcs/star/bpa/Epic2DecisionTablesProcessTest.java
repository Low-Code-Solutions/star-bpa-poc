package com.lcs.star.bpa;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcs.star.bpatest.ProcessTestApplication;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.EvaluateDecisionResponse;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * BPA-48 (E2-00): one test per rule of the four Epic 2 decision tables in {@code models/dmn}, 5 + 4 + 3 + 3 = 15. Each test evaluates
 * the deployed decision and checks the matched rule ID as well as the outputs, so a test proves its own rule and not only an output
 * that another rule could also give. The tests keep the names the Jira Definition of Done gives (E2_00_...). The input values are
 * boundary picks from the rule texts in the models (exactly 16 h, exactly 15 %, exactly 10 % ...), not business rules of their own.
 */
@SpringBootTest(classes = ProcessTestApplication.class)
@CamundaSpringProcessTest
@ActiveProfiles("test")
class Epic2DecisionTablesProcessTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    CamundaClient client;

    // ---- Decision_HoursWithinBudget (E2-04; hit policy FIRST: CBS, leave, daily limit, phase budget) ----

    @Test
    void E2_00_hoursCbsClosedBlocks() {
        Result r = hours(true, false, 4, 10, 100);
        assertThat(r.rule()).isEqualTo("Rule_CbsClosed");
        assertThat(r.output()).containsEntry("budgetResult", "Blocked").containsEntry("budgetReason", "CBS_CLOSED");
    }

    @Test
    void E2_00_hoursOnLeaveBlocks() {
        Result r = hours(false, true, 4, 10, 100);
        assertThat(r.rule()).isEqualTo("Rule_WorkerOnLeave");
        assertThat(r.output()).containsEntry("budgetResult", "Blocked").containsEntry("budgetReason", "WORKER_ON_LEAVE");
    }

    @Test
    void E2_00_hoursDailyLimitBlocks() {
        Result r = hours(false, false, 16.01, 10, 100); // 0.01 h above the daily limit of 16
        assertThat(r.rule()).isEqualTo("Rule_DailyLimitExceeded");
        assertThat(r.output()).containsEntry("budgetResult", "Blocked").containsEntry("budgetReason", "DAILY_LIMIT_EXCEEDED");
    }

    @Test
    void E2_00_hoursPhaseBudgetBlocks() {
        Result r = hours(false, false, 8, 100.01, 100); // 0.01 h over the phase budget
        assertThat(r.rule()).isEqualTo("Rule_PhaseBudgetExceeded");
        assertThat(r.output()).containsEntry("budgetResult", "Blocked").containsEntry("budgetReason", "PHASE_BUDGET_EXCEEDED");
    }

    @Test
    void E2_00_hoursAllowedAtLimits() {
        Result r = hours(false, false, 16, 100, 100); // exactly at the daily limit and exactly at the phase budget
        assertThat(r.rule()).isEqualTo("Rule_Allowed");
        assertThat(r.output()).containsEntry("budgetResult", "Allowed").containsEntry("budgetReason", null);
    }

    /** BPA-51 decision (a): the daily limit is the input dailyHoursLimit (app.daily-hours-limit), not a number in the table. */
    @Test
    void BPA51_dailyLimitFollowsTheDailyHoursLimitInput() {
        Result over = evaluate("Decision_HoursWithinBudget", Map.of("cbsClosed", false, "workerOnLeave", false, "dailyHoursAfter", 10.01,
                "dailyHoursLimit", 10, "phaseHoursAfter", 10, "phaseBudgetHours", 100));
        assertThat(over.rule()).isEqualTo("Rule_DailyLimitExceeded");
        Result at = evaluate("Decision_HoursWithinBudget", Map.of("cbsClosed", false, "workerOnLeave", false, "dailyHoursAfter", 10,
                "dailyHoursLimit", 10, "phaseHoursAfter", 10, "phaseBudgetHours", 100));
        assertThat(at.rule()).as("exactly at a limit of 10 is allowed").isEqualTo("Rule_Allowed");
    }

    // ---- Decision_ProgressRule (E2-11; FIRST: above 100, below 0, decrease without retraction, accepted) ----

    @Test
    void E2_00_progressAbove100Blocks() {
        Result r = progress(100.01, 90, false);
        assertThat(r.rule()).isEqualTo("Rule_AboveHundred");
        assertThat(r.output()).containsEntry("progressResult", "Blocked").containsEntry("progressReason", "ABOVE_100");
    }

    @Test
    void E2_00_progressBelow0Blocks() {
        Result r = progress(-0.01, 0, false);
        assertThat(r.rule()).isEqualTo("Rule_BelowZero");
        assertThat(r.output()).containsEntry("progressResult", "Blocked").containsEntry("progressReason", "BELOW_0");
    }

    @Test
    void E2_00_progressDecreaseNeedsRetraction() {
        Result r = progress(40, 50, false);
        assertThat(r.rule()).isEqualTo("Rule_DecreaseWithoutRetraction");
        assertThat(r.output()).containsEntry("progressResult", "NeedsRetraction").containsEntry("progressReason", "DECREASE");
    }

    @Test
    void E2_00_progressAccepted() {
        // exactly 100 % and an unchanged % are accepted; so is a decrease once a retraction is approved
        for (Result r : List.of(progress(100, 100, false), progress(40, 50, true))) {
            assertThat(r.rule()).isEqualTo("Rule_Accepted");
            assertThat(r.output()).containsEntry("progressResult", "Accepted").containsEntry("progressReason", null);
        }
    }

    // ---- Decision_ManhourVariance (E2-14; FIRST: no forecast, more than 15 %, within) ----

    @Test
    void E2_00_manhourNoForecast() {
        for (Double forecast : new Double[] {null, 0.0}) {
            Result r = manhour(forecast, 500);
            assertThat(r.rule()).isEqualTo("Rule_NoForecast");
            assertThat(r.output()).containsEntry("manhourAlert", false).containsEntry("manhourResult", "NO_FORECAST");
        }
    }

    @Test
    void E2_00_manhourOver15Alerts() {
        Result r = manhour(1000.0, 1150.01); // 15.001 %
        assertThat(r.rule()).isEqualTo("Rule_OverFifteen");
        assertThat(r.output()).containsEntry("manhourAlert", true).containsEntry("manhourResult", "OVER_15_PCT");
    }

    @Test
    void E2_00_manhourAt15NoAlert() {
        Result r = manhour(1000.0, 1150); // exactly 15 %: no alert
        assertThat(r.rule()).isEqualTo("Rule_Within");
        assertThat(r.output()).containsEntry("manhourAlert", false).containsEntry("manhourResult", "WITHIN");
    }

    // ---- Decision_CostVariance (E2-15; FIRST: no budget, more than 10 %, within) ----

    @Test
    void E2_00_costNoBudget() {
        for (Double budget : new Double[] {null, 0.0}) {
            Result r = cost(budget, 180000);
            assertThat(r.rule()).isEqualTo("Rule_NoBudget");
            assertThat(r.output()).containsEntry("costAlert", false).containsEntry("costResult", "NO_BUDGET");
        }
    }

    @Test
    void E2_00_costOver10Alerts() {
        Result r = cost(250000.0, 275000.01); // 10.000004 %
        assertThat(r.rule()).isEqualTo("Rule_OverTen");
        assertThat(r.output()).containsEntry("costAlert", true).containsEntry("costResult", "OVER_10_PCT");
    }

    @Test
    void E2_00_costAt10NoAlert() {
        Result r = cost(250000.0, 275000); // exactly 10 %: no alert
        assertThat(r.rule()).isEqualTo("Rule_Within");
        assertThat(r.output()).containsEntry("costAlert", false).containsEntry("costResult", "WITHIN");
    }

    // ---- helpers ----

    private Result hours(boolean cbsClosed, boolean workerOnLeave, double dailyHoursAfter, double phaseHoursAfter, double phaseBudgetHours) {
        return evaluate("Decision_HoursWithinBudget", Map.of("cbsClosed", cbsClosed, "workerOnLeave", workerOnLeave,
                "dailyHoursAfter", dailyHoursAfter, "dailyHoursLimit", 16, "phaseHoursAfter", phaseHoursAfter, "phaseBudgetHours", phaseBudgetHours));
    }

    private Result progress(double newPct, double previousPct, boolean retractionApproved) {
        return evaluate("Decision_ProgressRule", Map.of("newPct", newPct, "previousPct", previousPct, "retractionApproved", retractionApproved));
    }

    private Result manhour(Double currentForecastManhours, double actualManhours) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("currentForecastManhours", currentForecastManhours);
        variables.put("actualManhours", actualManhours);
        return evaluate("Decision_ManhourVariance", variables);
    }

    private Result cost(Double revisedBudgetCost, double actualCost) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("revisedBudgetCost", revisedBudgetCost);
        variables.put("actualCost", actualCost);
        return evaluate("Decision_CostVariance", variables);
    }

    private Result evaluate(String decisionId, Map<String, Object> variables) {
        EvaluateDecisionResponse response = client.newEvaluateDecisionCommand().decisionId(decisionId).variables(variables).send().join();
        assertThat(response.getEvaluatedDecisions()).as("one decision table evaluated").hasSize(1);
        var matched = response.getEvaluatedDecisions().get(0).getMatchedRules();
        assertThat(matched).as("hit policy FIRST: exactly one rule matches").hasSize(1);
        try {
            JsonNode output = JSON.readTree(response.getDecisionOutput());
            Map<String, Object> outputs = JSON.convertValue(output, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
            return new Result(matched.get(0).getRuleId(), outputs);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the decision output of " + decisionId, e);
        }
    }

    private record Result(String rule, Map<String, Object> output) {
    }
}
