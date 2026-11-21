package com.lcs.star.bpa;

import static com.lcs.star.bpa.CamundaSteps.activeElements;
import static com.lcs.star.bpa.CamundaSteps.json;
import static com.lcs.star.bpa.CamundaSteps.openTasks;
import static com.lcs.star.bpa.CamundaSteps.poll;
import static com.lcs.star.bpa.CamundaSteps.toJson;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.lcs.star.bpa.CamundaRest.Reply;
import com.lcs.star.bpa.progress.CompletionApprovalService;
import com.lcs.star.bpa.progress.RetractionDecisionService;
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
 * BPA-63 (E2-12): the Project Manager approves or declines a Work Pack's progress retraction. One test per Gherkin scenario, named as in the Jira Definition of
 * Done. Background: today is 20 Nov 2026 (Asia/Riyadh); the last locked week (12 Nov, locked by SQL: locking is BPA-67) holds WP-000457 30% and WP-000461 60%;
 * the report for the week ending 19 Nov gave WP-000457 40% and WP-000461 100%. PPC approves WP-000457 at 25% with the retraction reason "FMS double-counted
 * cutting" through the task API (BPA-60), BPA-61's real workers find the decrease, and the instance waits at UserTask_ApproveRetraction. The request is written
 * by the approval step (plan Q3), so every scenario starts from the real PPC completion.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(AssignmentFileApiTest.TestClockConfig.class)
class RetractionApiTest {

    static final Instant TODAY = Instant.parse("2026-11-20T07:00:00Z"); // 10:00 in Riyadh
    static final String PROCESS = "Process_PPC_WeeklyProgressPeriod";
    static final String APPROVE = "UserTask_ApproveCompletion";
    static final String RETRACTION = "UserTask_ApproveRetraction";
    static final String EARNED = "ServiceTask_CalculateEarnedManhours";
    static final String VARIANCE = "ServiceTask_PrepareVarianceCheck";
    static final String REVIEW = "UserTask_ReviewActualManhours"; // BPA-64: the variance worker now runs, so the instance moves on to the Cost Controller's review
    static final LocalDate W12 = LocalDate.of(2026, 11, 12);
    static final LocalDate W19 = LocalDate.of(2026, 11, 19);
    static final String REASON = "FMS double-counted cutting";

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

    @Autowired
    com.lcs.star.bpa.persistence.ProgressPeriodRepository periods;

    @Autowired
    RetractionDecisionService decisions;

    @Autowired
    CompletionApprovalService approvals;

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
                "ServiceTask_PrepareProgressCheck", "BusinessRuleTask_CheckProgressRule", "ServiceTask_RecordProgressResult", RETRACTION,
                "ServiceTask_RecordRetractionDecision", EARNED, VARIANCE, "BusinessRuleTask_CheckManhourVariance", "BusinessRuleTask_CheckCostVariance", "ServiceTask_SendVarianceAlerts", REVIEW, "ServiceTask_RecordCbsReview", "UserTask_ApproveWeeklyProgress"}) {
            CamundaSteps.cancelInstancesWaitingAt(element);
        }
    }

    @BeforeEach
    void background() {
        cancelWaiting();
        clock.set(TODAY);
        BookHoursFixture fixture = new BookHoursFixture(jdbc);
        BookHoursFixture.Ids ids = fixture.reset();
        fixture.completionBackground(); // WP-000457 and WP-000461 In Progress (A-2201)
        fixture.fmsBackground(ids); // WP-000470 In Progress
        flow = new AssignmentFlow(port, jdbc);
        // the last locked week (12 Nov): approved 30 / 60
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

    @Test
    void BPA63_retractionApprovedAcceptsTheDecrease() {
        toTheRetractionTask(Map.of("WP-000457", "25"), Map.of("WP-000457", REASON));

        HttpResponse<String> done = decide(RETRACTION_TASK(), "project-manager", List.of(decision("WP-000457", "Approved", null)));

        assertThat(done.statusCode()).as(done.body()).isEqualTo(204);
        poll(() -> ruleResult("WP-000457"), "Accepted"::equals, "rule_result Accepted of WP-000457");
        assertThat(jdbc.queryForObject("SELECT approved_pct FROM bpa.workpack_progress p JOIN bpa.workpack w ON w.id = p.workpack_id WHERE w.workpack_number = 'WP-000457' AND p.progress_period_id = ?",
                BigDecimal.class, period)).isEqualByComparingTo("25");
        poll(() -> activeElements(instance).stream().map(e -> e.path("elementId").asText()).toList(), active -> active.contains(VARIANCE) || active.contains(REVIEW), VARIANCE + " or " + REVIEW + " of " + instance);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.activity_progress WHERE progress_period_id = ? AND activity_ref = 'A-2201'", Integer.class, period))
                .as("Earned man-hours ran for week ending 19 Nov 2026").isEqualTo(1);
    }

    @Test
    void BPA63_retractionDeclinedGoesBackToPpc() {
        toTheRetractionTask(Map.of("WP-000457", "25"), Map.of("WP-000457", REASON));

        HttpResponse<String> done = decide(RETRACTION_TASK(), "project-manager", List.of(decision("WP-000457", "Declined", "Recheck with FMS first")));

        assertThat(done.statusCode()).as(done.body()).isEqualTo(204);
        awaitTask(instance, APPROVE);
        assertThat(declinedReason("WP-000457")).isEqualTo("Recheck with FMS first");
    }

    @Test
    void BPA63_declineNeedsAReason() {
        toTheRetractionTask(Map.of("WP-000457", "25"), Map.of("WP-000457", REASON));
        String retraction = RETRACTION_TASK();

        HttpResponse<String> refused = decide(retraction, "project-manager", List.of(decision("WP-000457", "Declined", "")));

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(json(refused.body()).path("detail").asText()).isEqualTo("Reason is required");
        assertThat(openTasks(instance, RETRACTION)).as("the task stays open").hasSize(1);
    }

    @Test
    void BPA63_decreaseWithoutReasonIsRefused() {
        HttpResponse<String> refused = approve(task, Map.of("WP-000457", "25"), Map.of("WP-000457", ""));

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(json(refused.body()).path("detail").asText()).isEqualTo("A retraction reason is required when completion falls below last week (30%)");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.progress_retraction", Integer.class)).as("no request row").isZero();
    }

    @Test
    void BPA63_retractionIsAudited() {
        toTheRetractionTask(Map.of("WP-000457", "25"), Map.of("WP-000457", REASON));
        assertThat(decide(RETRACTION_TASK(), "project-manager", List.of(decision("WP-000457", "Approved", null))).statusCode()).isEqualTo(204);

        Map<String, Object> row = poll(() -> retractionRows("WP-000457").stream().findFirst(), found -> found.isPresent() && found.get().get("decision") != null,
                "the decided retraction of WP-000457").orElseThrow();

        assertThat(row.get("requested_by")).isEqualTo("ppc-team");
        assertThat(row.get("reason")).isEqualTo(REASON);
        assertThat(row.get("decision")).isEqualTo("Approved");
        assertThat((BigDecimal) row.get("approved_pct")).isEqualByComparingTo("25");
        assertThat(row.get("decided_by")).isEqualTo("project-manager");
        assertThat(row.get("decided_at")).isNotNull();
        assertThat(row.get("task_key")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.audit_log WHERE table_name = 'progress_retraction' AND column_name = 'decision' AND new_value = 'Approved'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void BPA63_otherPersonasCannotApproveRetraction() {
        toTheRetractionTask(Map.of("WP-000457", "25"), Map.of("WP-000457", REASON));

        HttpResponse<String> refused = decide(RETRACTION_TASK(), "ppc-team", List.of(decision("WP-000457", "Approved", null)));

        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(openTasks(instance, RETRACTION)).as("the task stays open").hasSize(1);
    }

    @Test
    void BPA63_pmDecidesEachWorkPack() {
        toTheRetractionTask(Map.of("WP-000457", "25", "WP-000461", "55"), Map.of("WP-000457", REASON, "WP-000461", "Recount"));

        HttpResponse<String> done = decide(RETRACTION_TASK(), "project-manager",
                List.of(decision("WP-000457", "Approved", null), decision("WP-000461", "Declined", "Keep 60% until QC")));

        assertThat(done.statusCode()).as(done.body()).isEqualTo(204);
        awaitTask(instance, APPROVE);
        assertThat(retractionRows("WP-000457")).hasSize(1);
        assertThat(retractionRows("WP-000457").get(0).get("decision")).as("WP-000457's retraction to 25% stays Approved").isEqualTo("Approved");
        assertThat((BigDecimal) retractionRows("WP-000457").get(0).get("approved_pct")).isEqualByComparingTo("25");
        assertThat(declinedReason("WP-000461")).isEqualTo("Keep 60% until QC");
        assertThat(declinedReason("WP-000457")).isNull();
    }

    @Test
    void BPA63_approverMustNotBeRequester() {
        toTheRetractionTask(Map.of("WP-000457", "25"), Map.of("WP-000457", REASON));
        assertThat(jdbc.update("UPDATE bpa.progress_retraction SET requested_by = 'project-manager' WHERE decision IS NULL")).as("one open request to seed").isEqualTo(1); // plan Q4: only ppc-team can request

        HttpResponse<String> refused = decide(RETRACTION_TASK(), "project-manager", List.of(decision("WP-000457", "Approved", null)));

        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(json(refused.body()).path("detail").asText()).isEqualTo("You cannot approve a retraction you requested");
        assertThat(openTasks(instance, RETRACTION)).as("the task stays open").hasSize(1);
    }

    // --- extras (not Gherkin scenarios) ---------------------------------------------------------------------------------------

    @Test
    void aReApprovalAtADifferentPercentMakesANewRequest() {
        toTheRetractionTask(Map.of("WP-000457", "25"), Map.of("WP-000457", REASON));
        assertThat(decide(RETRACTION_TASK(), "project-manager", List.of(decision("WP-000457", "Declined", "Recheck with FMS first"))).statusCode()).isEqualTo(204);
        String again = awaitTask(instance, APPROVE);

        assertThat(approve(again, Map.of("WP-000457", "20"), Map.of("WP-000457", "Recount")).statusCode()).isEqualTo(204);

        awaitTask(instance, RETRACTION);
        List<Map<String, Object>> rows = retractionRows("WP-000457");
        assertThat(rows).as("the declined request is kept, a new one is open").hasSize(2);
        assertThat(rows).anySatisfy(r -> assertThat(r.get("decision")).isEqualTo("Declined"));
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.get("decision")).isNull();
            assertThat((BigDecimal) r.get("approved_pct")).isEqualByComparingTo("20");
            assertThat(r.get("reason")).isEqualTo("Recount");
        });
    }

    @Test
    void anApprovedPercentNeedsNoNewReason() {
        toTheRetractionTask(Map.of("WP-000457", "25", "WP-000461", "55"), Map.of("WP-000457", REASON, "WP-000461", "Recount"));
        assertThat(decide(RETRACTION_TASK(), "project-manager",
                List.of(decision("WP-000457", "Approved", null), decision("WP-000461", "Declined", "Keep 60% until QC"))).statusCode()).isEqualTo(204);
        String again = awaitTask(instance, APPROVE);

        HttpResponse<String> reApproved = approve(again, Map.of("WP-000457", "25", "WP-000461", "60"), Map.of());

        assertThat(reApproved.statusCode()).as("457 is below last week but its retraction to 25% is Approved: no new reason; 461 is back at last week's 60%").isEqualTo(204);
        assertThat(retractionRows("WP-000457")).as("no new request").hasSize(1);
    }

    @Test
    void aDeclineReasonOver250IsRefused() {
        toTheRetractionTask(Map.of("WP-000457", "25"), Map.of("WP-000457", REASON));

        HttpResponse<String> refused = decide(RETRACTION_TASK(), "project-manager", List.of(decision("WP-000457", "Declined", "x".repeat(251))));

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(json(refused.body()).path("detail").asText()).isEqualTo("Reason can be at most 250 characters");
    }

    @Test
    void aRetractionReasonOver250IsRefused() {
        HttpResponse<String> refused = approve(task, Map.of("WP-000457", "25"), Map.of("WP-000457", "x".repeat(251)));

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(json(refused.body()).path("detail").asText()).isEqualTo("Retraction reason can be at most 250 characters");
    }

    @Test
    void decisionsMustCoverEveryWaitingWorkPack() {
        toTheRetractionTask(Map.of("WP-000457", "25", "WP-000461", "55"), Map.of("WP-000457", REASON, "WP-000461", "Recount"));

        HttpResponse<String> refused = decide(RETRACTION_TASK(), "project-manager", List.of(decision("WP-000457", "Approved", null)));

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(json(refused.body()).path("detail").asText()).isEqualTo("Decisions must cover every Work Pack waiting for a retraction");
    }

    // --- extras written after the code, at service level (no red run: plan, "Progress and departures") -------------------------------

    @Test
    void aRerunRecordsNothingTwice() {
        toTheRetractionTask(Map.of("WP-000457", "25"), Map.of("WP-000457", REASON));
        assertThat(decide(RETRACTION_TASK(), "project-manager", List.of(decision("WP-000457", "Approved", null))).statusCode()).isEqualTo(204);
        long taskKey = ((Number) poll(() -> retractionRows("WP-000457").get(0).get("task_key"), key -> key != null, "the task key of the decided retraction")).longValue();
        int auditBefore = jdbc.queryForObject("SELECT count(*) FROM bpa.audit_log WHERE table_name = 'progress_retraction'", Integer.class);

        String again = decisions.decide(periods.find(period).orElseThrow(), List.of(new RetractionDecisionService.Decision("WP-000457", "Approved", null)), "project-manager", taskKey);

        assertThat(again).isEqualTo("Approved");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.audit_log WHERE table_name = 'progress_retraction'", Integer.class)).as("a retried job audits nothing again").isEqualTo(auditBefore);
        assertThat(retractionRows("WP-000457")).hasSize(1);
    }

    @Test
    void segregationAlsoFailsTheRecordStep() {
        toTheRetractionTask(Map.of("WP-000457", "25"), Map.of("WP-000457", REASON));
        jdbc.update("UPDATE bpa.progress_retraction SET requested_by = 'project-manager' WHERE decision IS NULL");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> decisions.decide(periods.find(period).orElseThrow(),
                List.of(new RetractionDecisionService.Decision("WP-000457", "Approved", null)), "project-manager", 99L))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("You cannot approve a retraction you requested");
        assertThat(retractionRows("WP-000457").get(0).get("decision")).as("nothing was written").isNull();
    }

    @Test
    void anOpenRequestFollowsTheReApprovalAndGoesWhenNotNeeded() {
        toTheRetractionTask(Map.of("WP-000457", "25"), Map.of("WP-000457", REASON));
        var week = periods.find(period).orElseThrow();

        approvals.record(week, List.of(new CompletionApprovalService.Approval("WP-000457", new BigDecimal("20"), "Changed by PPC", "Recount")), "ppc-team");

        assertThat(retractionRows("WP-000457")).as("the one open request is updated, not duplicated").hasSize(1);
        assertThat((BigDecimal) retractionRows("WP-000457").get(0).get("approved_pct")).isEqualByComparingTo("20");
        assertThat(retractionRows("WP-000457").get(0).get("reason")).isEqualTo("Recount");

        approvals.record(week, List.of(new CompletionApprovalService.Approval("WP-000457", new BigDecimal("30"), "Changed by PPC", null)), "ppc-team");

        assertThat(retractionRows("WP-000457")).as("at last week's % no retraction is needed").isEmpty();
    }

    /** The read model fields the screens use (QA review of BPA-63): the open request, then the Approved and Declined outcomes. Written after the code, no red run. */
    @Test
    void theReadModelShowsTheRequestAndItsOutcome() {
        toTheRetractionTask(Map.of("WP-000457", "25", "WP-000461", "55"), Map.of("WP-000457", REASON, "WP-000461", "Recount"));
        JsonNode open = rowOf("WP-000457");
        assertThat(open.path("retractionReason").asText()).isEqualTo(REASON);
        assertThat(open.path("retractionRequestedBy").asText()).isEqualTo("ppc-team");
        assertThat(open.path("retractionApproved").asBoolean()).isFalse();
        assertThat(open.has("retractionDeclinedReason")).isFalse();

        assertThat(decide(RETRACTION_TASK(), "project-manager",
                List.of(decision("WP-000457", "Approved", null), decision("WP-000461", "Declined", "Keep 60% until QC"))).statusCode()).isEqualTo(204);
        awaitTask(instance, APPROVE);

        JsonNode approved = rowOf("WP-000457");
        assertThat(approved.path("retractionApproved").asBoolean()).as("an Approved retraction is for the stored %").isTrue();
        assertThat(approved.has("retractionReason")).as("no open request any more").isFalse();
        JsonNode declined = rowOf("WP-000461");
        assertThat(declined.path("retractionApproved").asBoolean()).isFalse();
        assertThat(declined.path("retractionDeclinedReason").asText()).isEqualTo("Keep 60% until QC");
    }

    // --- helpers ----------------------------------------------------------------------------------------------------------------

    /** PPC approves the week (changes with their retraction reasons) and the instance waits at the retraction task. */
    private void toTheRetractionTask(Map<String, String> changes, Map<String, String> reasons) {
        HttpResponse<String> done = approve(task, changes, reasons);
        assertThat(done.statusCode()).as("PPC approves with the retraction reason: " + done.body()).isEqualTo(204);
        awaitTask(instance, RETRACTION);
    }

    private String RETRACTION_TASK() {
        return awaitTask(instance, RETRACTION);
    }

    private static Map<String, Object> decision(String workpack, String decision, String reason) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("workpackNumber", workpack);
        entry.put("retractionDecision", decision);
        if (reason != null) {
            entry.put("reason", reason);
        }
        return entry;
    }

    private HttpResponse<String> decide(String taskKey, String persona, List<Map<String, Object>> decisions) {
        return flow.loggedInAs(persona).post("/api/v1/tasks/" + taskKey + "/complete", toJson(Map.of("variables", Map.of("retractionDecisions", decisions))));
    }

    private long startAt(String elementId, Map<String, Object> variables) {
        Reply started = IntegrationApp.admin.post("/v2/process-instances", Map.of(
                "processDefinitionId", PROCESS, "variables", variables, "startInstructions", List.of(Map.of("elementId", elementId))));
        assertThat(started.status()).as("start at " + elementId + ": " + started.body()).isEqualTo(200);
        return Long.parseLong(started.body().path("processInstanceKey").asText());
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

    /**
     * PPC approves the week through the task API: every Work Pack at its pre-filled %, except those in {@code changes} (with a remark, as BPA-60 asks) and, for
     * a change with an entry in {@code reasons}, that retraction reason (an empty text is sent as it is).
     */
    private HttpResponse<String> approve(String taskKey, Map<String, String> changes, Map<String, String> reasons) {
        HttpResponse<String> week = flow.loggedInAs("ppc-team").get("/api/v1/progress-periods/2026-11-19/workpacks");
        assertThat(week.statusCode()).as(week.body()).isEqualTo(200);
        List<Map<String, Object>> approvals = new ArrayList<>();
        for (JsonNode row : json(week.body()).path("workpacks")) {
            String workpack = row.path("workpackNumber").asText();
            Map<String, Object> approval = new LinkedHashMap<>();
            approval.put("workpackNumber", workpack);
            if (changes.containsKey(workpack)) {
                approval.put("approvedPct", new BigDecimal(changes.get(workpack)));
                BigDecimal reference = row.path("noReportRow").asBoolean() || !row.hasNonNull("reportedPct") ? row.path("lastLockedPct").decimalValue() : row.path("reportedPct").decimalValue();
                if (new BigDecimal(changes.get(workpack)).compareTo(reference) != 0) { // the remark rule of BPA-60 compares with the reported % (or the last locked %)
                    approval.put("remark", "Changed by PPC");
                }
            } else {
                approval.put("approvedPct", row.path("approvedPct").decimalValue());
            }
            if (reasons.containsKey(workpack)) {
                approval.put("retractionReason", reasons.get(workpack));
            }
            approvals.add(approval);
        }
        return flow.loggedInAs("ppc-team").post("/api/v1/tasks/" + taskKey + "/complete", toJson(Map.of("variables", Map.of("completionApprovals", approvals))));
    }

    private String ruleResult(String workpack) {
        List<String> rows = jdbc.query("""
                SELECT p.rule_result FROM bpa.workpack_progress p JOIN bpa.workpack w ON w.id = p.workpack_id WHERE w.workpack_number = ? AND p.progress_period_id = ?""",
                (rs, n) -> rs.getString("rule_result"), workpack, period);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** The retraction requests of the Work Pack for the week, oldest first. */
    private List<Map<String, Object>> retractionRows(String workpack) {
        return jdbc.queryForList("""
                SELECT r.requested_by, r.reason, r.approved_pct, r.decision, r.decision_reason, r.decided_by, r.decided_at, r.task_key
                FROM bpa.progress_retraction r JOIN bpa.workpack_progress p ON p.id = r.workpack_progress_id JOIN bpa.workpack w ON w.id = p.workpack_id
                WHERE w.workpack_number = ? AND p.progress_period_id = ? ORDER BY r.created_at""", workpack, period);
    }
    private JsonNode rowOf(String workpack) {
        for (JsonNode row : json(flow.loggedInAs("ppc-team").get("/api/v1/progress-periods/2026-11-19/workpacks").body()).path("workpacks")) {
            if (workpack.equals(row.path("workpackNumber").asText())) {
                return row;
            }
        }
        throw new AssertionError(workpack + " is not in the week's rows");
    }


    /** What PPC sees for the Work Pack: the read model's retractionDeclinedReason (plan Q5), null when absent. */
    private String declinedReason(String workpack) {
        JsonNode week = json(flow.loggedInAs("ppc-team").get("/api/v1/progress-periods/2026-11-19/workpacks").body());
        for (JsonNode row : week.path("workpacks")) {
            if (workpack.equals(row.path("workpackNumber").asText())) {
                return row.hasNonNull("retractionDeclinedReason") ? row.path("retractionDeclinedReason").asText() : null;
            }
        }
        throw new AssertionError(workpack + " is not in the week's rows");
    }
}
