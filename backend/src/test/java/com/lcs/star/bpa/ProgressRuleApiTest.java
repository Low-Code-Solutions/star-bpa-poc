package com.lcs.star.bpa;

import static com.lcs.star.bpa.CamundaSteps.activeElements;
import static com.lcs.star.bpa.CamundaSteps.assertStaysEmpty;
import static com.lcs.star.bpa.CamundaSteps.json;
import static com.lcs.star.bpa.CamundaSteps.openTasks;
import static com.lcs.star.bpa.CamundaSteps.poll;
import static com.lcs.star.bpa.CamundaSteps.toJson;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.lcs.star.bpa.CamundaRest.Reply;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BPA-61 (E2-11): the System checks every Work Pack's approved % against FRD-21 §21.9.1 with Decision_ProgressRule. One test per Gherkin scenario, named as in
 * the Jira Definition of Done (the Scenario Outline is one test). Background: today is 20 Nov 2026 (Asia/Riyadh); the last locked week (12 Nov, locked by SQL:
 * locking is BPA-67) holds WP-000457 30% and WP-000461 60%, WP-000470 none; the report for the week ending 19 Nov gave WP-000457 40% and WP-000461 100%.
 * Two paths (plan Q1, an ASSUMPTION): the real one starts the weekly instance at UserTask_ApproveCompletion and completes it through the task API (BPA-60),
 * so the approval is recorded and then checked; the Blocked scenarios cannot come that way (BPA-60 refuses a % outside 0 to 100 and the column cannot hold it),
 * so they start an instance at BusinessRuleTask_CheckProgressRule with a {@code progressChecks} variable, and the real decision, record step and gateway run.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(AssignmentFileApiTest.TestClockConfig.class)
class ProgressRuleApiTest {

    static final Instant TODAY = Instant.parse("2026-11-20T07:00:00Z"); // 10:00 in Riyadh
    static final String PROCESS = "Process_PPC_WeeklyProgressPeriod";
    static final String APPROVE = "UserTask_ApproveCompletion";
    static final String RULE = "BusinessRuleTask_CheckProgressRule";
    static final String RETRACTION = "UserTask_ApproveRetraction";
    static final String EARNED = "ServiceTask_CalculateEarnedManhours";
    static final LocalDate W12 = LocalDate.of(2026, 11, 12);
    static final LocalDate W19 = LocalDate.of(2026, 11, 19);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void camunda(DynamicPropertyRegistry registry) {
        IntegrationApp.background();
        registry.add("camunda.client.rest-address", IntegrationCamunda::restUrl);
        registry.add("camunda.client.grpc-address", IntegrationCamunda::grpcUrl);
        registry.add("bpa.camunda.rest-url", IntegrationCamunda::restUrl);
        registry.add("camunda.client.auth.password", () -> IntegrationApp.servicePassword);
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AssignmentFileApiTest.TestClock clock;

    private AssignmentFlow flow;
    private long instance;
    private String task;
    private UUID period;

    /** All test classes share one Camunda: no weekly instance left waiting by another class may take this class's tasks. */
    @BeforeAll
    static void noInstanceLeftWaiting() {
        IntegrationApp.background();
        cancelWaiting();
    }

    private static void cancelWaiting() {
        for (String element : new String[] {"ServiceTask_OpenProgressPeriod", "ServiceTask_GenerateFmsHoursFile", "UserTask_SendHoursToFms",
                "ServiceTask_RecordFmsHandOff", "UserTask_UploadCompletionReport", "ServiceTask_ReadCompletionReport", APPROVE, "ServiceTask_RecordCompletionApproval",
                "ServiceTask_PrepareProgressCheck", RULE, "ServiceTask_RecordProgressResult", RETRACTION, EARNED}) {
            CamundaSteps.cancelInstancesWaitingAt(element);
        }
    }

    @BeforeEach
    void background() {
        cancelWaiting();
        clock.set(TODAY);
        BookHoursFixture fixture = new BookHoursFixture(jdbc);
        BookHoursFixture.Ids ids = fixture.reset();
        fixture.completionBackground(); // WP-000457 and WP-000461 In Progress
        fixture.fmsBackground(ids); // WP-000470 In Progress
        flow = new AssignmentFlow(port, jdbc);
        // the last locked week (12 Nov): approved 30 / 60; WP-000470 has none
        jdbc.update("UPDATE bpa.progress_period SET status = 'Locked', locked_at = now() WHERE week_ending = ?", java.sql.Date.valueOf(W12));
        UUID locked = jdbc.queryForObject("SELECT id FROM bpa.progress_period WHERE week_ending = ?", UUID.class, java.sql.Date.valueOf(W12));
        approved(locked, "WP-000457", "30");
        approved(locked, "WP-000461", "60");
        // the week ending 19 Nov: the instance waits at the approval task; the report gave 40% and 100%
        instance = startAt(APPROVE, Map.of());
        insertPeriod(W19, instance);
        period = jdbc.queryForObject("SELECT id FROM bpa.progress_period WHERE week_ending = ?", UUID.class, java.sql.Date.valueOf(W19));
        reported(period, "WP-000457", "40");
        reported(period, "WP-000461", "100");
        task = awaitTask(instance, APPROVE);
    }

    // --- the scenarios ----------------------------------------------------------------------------------------------------

    /** The Scenario Outline: 100 is accepted (the real path); 100.01 is blocked (the rule-task path, plan Q1). */
    @Test
    void BPA61_exactly100IsAccepted() {
        assertThat(approve(Map.of("WP-000461", "100")).statusCode()).isEqualTo(204);
        awaitRuleResult("WP-000461", "Accepted");

        long blocked = ruleTaskInstance(List.of(check("WP-000461", "100.01", "60", false)));

        awaitTask(blocked, APPROVE);
        assertThat(ruleResult("WP-000461")).isEqualTo("Blocked");
    }

    @Test
    void BPA61_above100IsBlocked() {
        long blocked = ruleTaskInstance(List.of(check("WP-000461", "120", "60", false)));

        String back = awaitTask(blocked, APPROVE);

        assertThat(messagesOf(back)).contains("WP-000461 completion cannot exceed 100% (approved 120%)");
    }

    @Test
    void BPA61_unchangedPercentIsAccepted() {
        assertThat(approve(Map.of("WP-000457", "30")).statusCode()).isEqualTo(204);

        awaitRuleResult("WP-000457", "Accepted");
    }

    @Test
    void BPA61_decreaseNeedsRetraction() {
        assertThat(approve(Map.of("WP-000457", "25")).statusCode()).isEqualTo(204);

        awaitRuleResult("WP-000457", "NeedsRetraction");

        String retraction = awaitTask(instance, RETRACTION);
        List<String> groups = new ArrayList<>();
        openTasks(instance, RETRACTION).get(0).path("candidateGroups").forEach(g -> groups.add(g.asText()));
        assertThat(groups).containsExactly("project-manager");
        assertThat(retraction).isNotBlank();
    }

    @Test
    void BPA61_firstWeekStartsFromZero() {
        assertThat(approve(Map.of("WP-000470", "10")).statusCode()).isEqualTo(204);

        awaitRuleResult("WP-000470", "Accepted");
    }

    @Test
    void BPA61_blockedWinsOverRetraction() {
        long blocked = ruleTaskInstance(List.of(check("WP-000461", "120", "60", false), check("WP-000457", "25", "30", false)));

        awaitTask(blocked, APPROVE);

        assertStaysEmpty(() -> openTasks(blocked, RETRACTION), "a " + RETRACTION + " task of the blocked instance");
    }

    @Test
    void BPA61_allAcceptedGoesToEarnedManhours() {
        assertThat(approve(Map.of()).statusCode()).as("the report's 40% and 100%").isEqualTo(204);

        poll(() -> activeElements(instance).stream().map(e -> e.path("elementId").asText()).toList(), active -> active.contains(EARNED), EARNED + " of " + instance);
    }

    @Test
    void BPA61_retractionValidOnlyForApprovedPercent() {
        retraction("WP-000457", "25", "Approved"); // the Project Manager approved a decrease to 25 ...
        assertThat(approve(Map.of("WP-000457", "20")).statusCode()).isEqualTo(204); // ... and PPC now approves 20

        awaitRuleResult("WP-000457", "NeedsRetraction");

        awaitTask(instance, RETRACTION);
    }

    @Test
    void BPA61_belowZeroBlockedBeforeRetraction() {
        long blocked = ruleTaskInstance(List.of(check("WP-000457", "-5", "30", false)));

        String back = awaitTask(blocked, APPROVE);

        assertThat(ruleResult("WP-000457")).isEqualTo("Blocked");
        assertThat(messagesOf(back)).contains("WP-000457 completion cannot be below 0%");
    }

    // --- extras (not Gherkin scenarios) ---------------------------------------------------------------------------------------

    @Test
    void anApprovedRetractionForTheSamePercentIsAccepted() {
        retraction("WP-000457", "25", "Approved");
        assertThat(approve(Map.of("WP-000457", "25")).statusCode()).isEqualTo(204);

        awaitRuleResult("WP-000457", "Accepted");
    }

    // --- helpers ----------------------------------------------------------------------------------------------------------------

    private long startAt(String elementId, Map<String, Object> variables) {
        Reply started = IntegrationApp.admin.post("/v2/process-instances", Map.of(
                "processDefinitionId", PROCESS, "variables", variables, "startInstructions", List.of(Map.of("elementId", elementId))));
        assertThat(started.status()).as("start at " + elementId + ": " + started.body()).isEqualTo(200);
        return Long.parseLong(started.body().path("processInstanceKey").asText());
    }

    /** An instance started at the rule task with the given checks, for the week ending 19 Nov (its period is found from the variable, plan departure). */
    private long ruleTaskInstance(List<Map<String, Object>> checks) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("progressPeriodId", period.toString());
        variables.put("weekEnding", W19.toString());
        variables.put("progressChecks", checks);
        return startAt(RULE, variables);
    }

    private static Map<String, Object> check(String workpack, String newPct, String previousPct, boolean retractionApproved) {
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("workpackNumber", workpack);
        check.put("newPct", new BigDecimal(newPct));
        check.put("previousPct", new BigDecimal(previousPct));
        check.put("retractionApproved", retractionApproved);
        return check;
    }

    private void insertPeriod(LocalDate weekEnding, long processInstanceKey) {
        jdbc.update("""
                INSERT INTO bpa.progress_period (week_ending, cut_off_at, status, process_instance_key, created_by, updated_by)
                VALUES (?, ?, 'CutOff', ?, 'test', 'test')""", java.sql.Date.valueOf(weekEnding),
                java.sql.Timestamp.from(weekEnding.atTime(20, 59).toInstant(java.time.ZoneOffset.UTC)), processInstanceKey);
    }

    private String awaitTask(long forInstance, String elementId) {
        return poll(() -> openTasks(forInstance, elementId).stream().findFirst(), found -> found.isPresent(), elementId + " of " + forInstance).orElseThrow()
                .path("userTaskKey").asText();
    }

    private void approved(UUID periodId, String workpack, String pct) {
        jdbc.update("""
                INSERT INTO bpa.workpack_progress (workpack_id, progress_period_id, approved_pct, approved_by, approved_at, created_by, updated_by)
                SELECT id, ?, ?::numeric, 'test', now(), 'test', 'test' FROM bpa.workpack WHERE workpack_number = ?""", periodId, pct, workpack);
    }

    private void reported(UUID periodId, String workpack, String pct) {
        jdbc.update("""
                INSERT INTO bpa.workpack_progress (workpack_id, progress_period_id, reported_pct, created_by, updated_by)
                SELECT id, ?, ?::numeric, 'test', 'test' FROM bpa.workpack WHERE workpack_number = ?""", periodId, pct, workpack);
    }

    /** An earlier retraction of the Work Pack for the week, with a decision (the Project Manager's, BPA-63 will write it). */
    private void retraction(String workpack, String pct, String decision) {
        jdbc.update("""
                INSERT INTO bpa.progress_retraction (workpack_progress_id, requested_by, reason, approved_pct, decision, decided_by, decided_at, task_key, created_by, updated_by)
                SELECT p.id, 'ppc-team', 'FMS double-counted cutting', ?::numeric, ?, 'project-manager', now(), 1, 'test', 'test'
                FROM bpa.workpack_progress p JOIN bpa.workpack w ON w.id = p.workpack_id WHERE w.workpack_number = ? AND p.progress_period_id = ?""", pct, decision,
                workpack, period);
    }

    /** PPC approves the week through the task API: every Work Pack at its pre-filled %, except those in {@code changes} (with a remark, as BPA-60 asks). */
    private HttpResponse<String> approve(Map<String, String> changes) {
        HttpResponse<String> week = flow.loggedInAs("ppc-team").get("/api/v1/progress-periods/2026-11-19/workpacks");
        assertThat(week.statusCode()).as(week.body()).isEqualTo(200);
        List<Map<String, Object>> approvals = new ArrayList<>();
        for (JsonNode row : json(week.body()).path("workpacks")) {
            String workpack = row.path("workpackNumber").asText();
            Map<String, Object> approval = new LinkedHashMap<>();
            approval.put("workpackNumber", workpack);
            if (changes.containsKey(workpack)) {
                approval.put("approvedPct", new BigDecimal(changes.get(workpack)));
                approval.put("remark", "Changed by PPC");
            } else {
                approval.put("approvedPct", row.path("approvedPct").decimalValue());
            }
            approvals.add(approval);
        }
        return flow.loggedInAs("ppc-team").post("/api/v1/tasks/" + task + "/complete", toJson(Map.of("variables", Map.of("completionApprovals", approvals))));
    }

    private String ruleResult(String workpack) {
        // null while the row does not exist yet (WP-000470 gets its row from BPA-60's record step) or has no result
        List<String> rows = jdbc.query("""
                SELECT p.rule_result FROM bpa.workpack_progress p JOIN bpa.workpack w ON w.id = p.workpack_id WHERE w.workpack_number = ? AND p.progress_period_id = ?""",
                (rs, n) -> rs.getString("rule_result"), workpack, period);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void awaitRuleResult(String workpack, String expected) {
        poll(() -> String.valueOf(ruleResult(workpack)), result -> expected.equals(result), "rule_result " + expected + " of " + workpack);
    }

    /** The messages the task shows (the task API's validationMessages). */
    private List<String> messagesOf(String taskKey) {
        List<String> messages = new ArrayList<>();
        json(flow.loggedInAs("ppc-team").get("/api/v1/tasks/" + taskKey).body()).path("validationMessages").forEach(m -> messages.add(m.asText()));
        return messages;
    }
}
