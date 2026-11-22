package com.lcs.star.bpa;

import static com.lcs.star.bpa.CamundaSteps.activeElements;
import static com.lcs.star.bpa.CamundaSteps.json;
import static com.lcs.star.bpa.CamundaSteps.openTasks;
import static com.lcs.star.bpa.CamundaSteps.poll;
import static com.lcs.star.bpa.CamundaSteps.toJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.lcs.star.bpa.CamundaRest.Reply;
import com.lcs.star.bpa.progress.WeekDecisionService;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
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
 * BPA-66 (E2-19): the Project Manager approves a week's progress or sends it back before the period locks. One test per Gherkin scenario, named as in the Jira
 * Definition of Done. Background: today is 20 Nov 2026 (Asia/Riyadh); for the week ending 19 Nov 2026 PPC approved WP-000457 at 40% and WP-000461 at 100%
 * (last locked week, 12 Nov: 30% and 60%); the instance is started at ServiceTask_CalculateEarnedManhours, so Earned man-hours, the variance check and the alerts
 * run for real, cost-controller completes the review, and the instance waits at UserTask_ApproveWeeklyProgress. The lock is BPA-67 (no worker yet): an approved
 * week is proved by the instance reaching ServiceTask_CompleteWorkPacks (plan Q2).
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(AssignmentFileApiTest.TestClockConfig.class)
class WeekApprovalApiTest {

    static final Instant TODAY = Instant.parse("2026-11-20T07:00:00Z"); // 10:00 in Riyadh
    static final String PROCESS = "Process_PPC_WeeklyProgressPeriod";
    static final String EARNED = "ServiceTask_CalculateEarnedManhours";
    static final String REVIEW = "UserTask_ReviewActualManhours";
    static final String APPROVE_WEEK = "UserTask_ApproveWeeklyProgress";
    static final String COMPLETION = "UserTask_ApproveCompletion";
    static final String RECORD_DECISION = "ServiceTask_RecordWeekDecision";
    static final String COMPLETE_WORKPACKS = "ServiceTask_CompleteWorkPacks";
    static final LocalDate W12 = LocalDate.of(2026, 11, 12);
    static final LocalDate W19 = LocalDate.of(2026, 11, 19);
    static final String PIP01 = "CBS-139-PIP-01";
    static final String PCON = "pcon.manager@star.example";
    static final String PUNCH_LIST = "WP-000461 cannot be 100%: punch list open";
    static final String SEGREGATION = "You cannot approve a week whose progress you approved";

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
        registry.add("spring.mail.host", Mailpit::host);
        registry.add("spring.mail.port", Mailpit::smtpPort);
        registry.add("bpa.alerts.pcon-manager-email", () -> PCON);
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AssignmentFileApiTest.TestClock clock;

    @Autowired
    WeekDecisionService decisions;

    private AssignmentFlow flow;
    private BookHoursFixture fixture;
    private BookHoursFixture.FmsIds sheets;
    private long instance;
    private UUID period;
    private String weekTask;

    /** All test classes share one Camunda: no weekly instance left waiting by another class may take this class's tasks. */
    @BeforeAll
    static void noInstanceLeftWaiting() {
        Mailpit.start();
        IntegrationApp.background();
        cancelWaiting();
    }

    private static void cancelWaiting() {
        for (String element : new String[] {"ServiceTask_OpenProgressPeriod", "ServiceTask_GenerateFmsHoursFile", "UserTask_SendHoursToFms", "ServiceTask_RecordFmsHandOff",
                "UserTask_UploadCompletionReport", "ServiceTask_ReadCompletionReport", COMPLETION, "ServiceTask_RecordCompletionApproval", "ServiceTask_PrepareProgressCheck",
                "BusinessRuleTask_CheckProgressRule", "ServiceTask_RecordProgressResult", "UserTask_ApproveRetraction", "ServiceTask_RecordRetractionDecision", EARNED,
                "ServiceTask_PrepareVarianceCheck", "BusinessRuleTask_CheckManhourVariance", "BusinessRuleTask_CheckCostVariance", "ServiceTask_SendVarianceAlerts", REVIEW,
                "ServiceTask_RecordCbsReview", APPROVE_WEEK, RECORD_DECISION, COMPLETE_WORKPACKS}) {
            CamundaSteps.cancelInstancesWaitingAt(element);
        }
    }

    @BeforeEach
    void background() {
        cancelWaiting();
        clock.set(TODAY);
        fixture = new BookHoursFixture(jdbc);
        BookHoursFixture.Ids ids = fixture.reset();
        fixture.completionBackground(); // WP-000457 and WP-000461 In Progress (A-2201)
        sheets = fixture.fmsBackground(ids); // CBS-139-PIP-01 on E1021 and E1099; WP-000470 In Progress
        new RevisionFlow(port, jdbc).seedAppUsers();
        flow = new AssignmentFlow(port, jdbc);
        // the last locked week (12 Nov): approved 30 / 60
        jdbc.update("UPDATE bpa.progress_period SET status = 'Locked', locked_at = now() WHERE week_ending = ?", java.sql.Date.valueOf(W12));
        UUID locked = jdbc.queryForObject("SELECT id FROM bpa.progress_period WHERE week_ending = ?", UUID.class, java.sql.Date.valueOf(W12));
        approved(locked, "WP-000457", "30");
        approved(locked, "WP-000461", "60");
    }

    // --- the scenarios ----------------------------------------------------------------------------------------------------

    @Test
    void BPA66_projectManagerApprovesTheWeek() {
        toTheWeekTask(false);

        HttpResponse<String> done = decide("project-manager", "Approved", null, null);

        assertThat(done.statusCode()).as(done.body()).isEqualTo(204);
        poll(() -> activeNames(), active -> active.contains(COMPLETE_WORKPACKS), COMPLETE_WORKPACKS + " of " + instance);
        Map<String, Object> row = jdbc.queryForMap("SELECT week_approved_by, week_approved_at, status FROM bpa.progress_period WHERE id = ?", period);
        assertThat(row.get("week_approved_by")).isEqualTo("project-manager");
        assertThat((Timestamp) row.get("week_approved_at")).isEqualTo(Timestamp.from(TODAY));
        assertThat(row.get("status")).as("the lock is BPA-67").isEqualTo("CutOff");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.audit_log WHERE table_name = 'progress_period' AND record_id = ?", Integer.class, period))
                .as("one audit row per changed column").isGreaterThanOrEqualTo(2);
    }

    @Test
    void BPA66_changesRequestedGoBackToPpc() {
        toTheWeekTask(false);

        HttpResponse<String> done = decide("project-manager", "ChangesRequested", "Progress", PUNCH_LIST);

        assertThat(done.statusCode()).as(done.body()).isEqualTo(204);
        String back = awaitTask(instance, COMPLETION);
        JsonNode task = json(flow.loggedInAs("ppc-team").get("/api/v1/tasks/" + back).body());
        assertThat(task.path("variables").path("weekDecision").asText()).isEqualTo("ChangesRequested");
        assertThat(task.path("variables").path("weekChangeTarget").asText()).isEqualTo("Progress");
        assertThat(task.path("variables").path("weekDecisionReason").asText()).isEqualTo(PUNCH_LIST);
        Map<String, Object> row = jdbc.queryForMap("SELECT week_decision_reason, week_approved_by, status FROM bpa.progress_period WHERE id = ?", period);
        assertThat(row.get("week_decision_reason")).isEqualTo(PUNCH_LIST);
        assertThat(row.get("week_approved_by")).as("a send-back is not an approval").isNull();
        assertThat(row.get("status")).as("the period is not locked").isEqualTo("CutOff");
    }

    @Test
    void BPA66_changesNeedAReason() {
        toTheWeekTask(false);

        HttpResponse<String> refused = decide("project-manager", "ChangesRequested", "Progress", "");

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(json(refused.body()).path("detail").asText()).isEqualTo("Reason is required");
        assertThat(openTasks(instance, APPROVE_WEEK)).as("the task stays open").hasSize(1);
    }

    @Test
    void BPA66_rerunAfterChangesRecalculates() {
        toTheWeekTask(true);
        BigDecimal before = earned("A-2201");
        int alertsBefore = alertRows();
        assertThat(decide("project-manager", "ChangesRequested", "Progress", PUNCH_LIST).statusCode()).isEqualTo(204);
        String ppc = awaitTask(instance, COMPLETION);

        HttpResponse<String> approvedAgain = ppcApprove(ppc, "WP-000461", "95");
        assertThat(approvedAgain.statusCode()).as(approvedAgain.body()).isEqualTo(204);
        String review = awaitTask(instance, REVIEW);
        assertThat(completeReview(review, "Rework on spools").statusCode()).isEqualTo(204);

        String second = awaitTask(instance, APPROVE_WEEK);
        assertThat(second).as("a new task, not the first one").isNotEqualTo(weekTask);
        Map<String, Object> row = jdbc.queryForMap("SELECT actual_pct, earned_manhours FROM bpa.activity_progress WHERE activity_ref = 'A-2201' AND progress_period_id = ?", period);
        assertThat((BigDecimal) row.get("actual_pct")).isEqualByComparingTo("49.17"); // (40 x 500 + 95 x 100) / 600
        assertThat((BigDecimal) row.get("earned_manhours")).isEqualByComparingTo("590.00").isNotEqualByComparingTo(before);
        assertThat(alertRows()).as("alerts are never sent twice for the week").isEqualTo(alertsBefore);
        List<String> groups = new ArrayList<>();
        openTasks(instance, APPROVE_WEEK).get(0).path("candidateGroups").forEach(g -> groups.add(g.asText()));
        assertThat(groups).containsExactly("project-manager");
    }

    @Test
    void BPA66_weekShowsAlertsAndRemarks() {
        toTheWeekTask(true);

        JsonNode cbs = cbsRow(PIP01);

        assertThat(cbs.path("alert").asBoolean()).as("man-hour alert").isTrue();
        assertThat(cbs.path("costAlert").asBoolean()).as("cost alert").isTrue();
        assertThat(cbs.path("remark").asText()).isEqualTo("Rework on spools");
        HttpResponse<String> earned = flow.loggedInAs("project-manager").get("/api/v1/progress-periods/2026-11-19/earned-manhours");
        assertThat(earned.statusCode()).as("the PM's screen shows Earned man-hours").isEqualTo(200);
        assertThat(json(earned.body()).path("activities")).isNotEmpty();
    }

    @Test
    void BPA66_otherPersonasCannotApproveTheWeek() {
        toTheWeekTask(false);

        HttpResponse<String> refused = decide("ppc-team", "Approved", null, null);

        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(openTasks(instance, APPROVE_WEEK)).as("the task stays open").hasSize(1);
    }

    @Test
    void BPA66_changesCanGoBackToCostReview() {
        toTheWeekTask(false);
        BigDecimal pct = approvedPct("WP-000461");

        HttpResponse<String> done = decide("project-manager", "ChangesRequested", "CostReview", "Explain the spool rework cost");

        assertThat(done.statusCode()).as(done.body()).isEqualTo(204);
        String back = awaitTask(instance, REVIEW);
        JsonNode task = json(flow.loggedInAs("cost-controller").get("/api/v1/tasks/" + back).body());
        List<String> groups = new ArrayList<>();
        openTasks(instance, REVIEW).get(0).path("candidateGroups").forEach(g -> groups.add(g.asText()));
        assertThat(groups).containsExactly("cost-controller");
        assertThat(task.path("variables").path("weekChangeTarget").asText()).isEqualTo("CostReview");
        assertThat(task.path("variables").path("weekDecisionReason").asText()).isEqualTo("Explain the spool rework cost");
        assertThat(approvedPct("WP-000461")).as("the approved % are unchanged").isEqualByComparingTo(pct);
        assertThat(openTasks(instance, COMPLETION)).isEmpty();
    }

    @Test
    void BPA66_approverMustNotBePercentApprover() {
        toTheWeekTask(false);
        jdbc.update("UPDATE bpa.workpack_progress SET approved_by = 'project-manager' WHERE progress_period_id = ?", period);

        HttpResponse<String> refused = decide("project-manager", "Approved", null, null);

        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(json(refused.body()).path("detail").asText()).isEqualTo(SEGREGATION);
        assertThat(openTasks(instance, APPROVE_WEEK)).as("the task stays open").hasSize(1);
        assertThat(jdbc.queryForObject("SELECT week_approved_by FROM bpa.progress_period WHERE id = ?", String.class, period)).as("nothing is written").isNull();
    }

    // --- extras (not Gherkin scenarios) ---------------------------------------------------------------------------------------

    /** The record step is the backstop of the task API's 409 (as BPA-63): it refuses the % approver itself, whatever the task API did. */
    @Test
    void segregationAlsoFailsTheRecordStep() {
        toTheWeekTask(false);
        jdbc.update("UPDATE bpa.workpack_progress SET approved_by = 'project-manager' WHERE progress_period_id = ?", period);

        assertThatThrownBy(() -> decisions.record(period, "Approved", null, null, "project-manager")).isInstanceOf(IllegalArgumentException.class).hasMessage(SEGREGATION);
        assertThat(jdbc.queryForObject("SELECT week_approved_by FROM bpa.progress_period WHERE id = ?", String.class, period)).isNull();
    }

    @Test
    void aChangesRequestNeedsATarget() {
        toTheWeekTask(false);

        HttpResponse<String> refused = decide("project-manager", "ChangesRequested", null, PUNCH_LIST); // ASSUMPTION text (plan Q8)

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(json(refused.body()).path("detail").asText()).isEqualTo("Target is required");
        assertThat(openTasks(instance, APPROVE_WEEK)).hasSize(1);
    }

    /** A job retry of the record step for the same decision writes nothing new (re-run key: the values). */
    @Test
    void aRerunOfRecordWeekDecisionChangesNothing() {
        toTheWeekTask(false);
        assertThat(decide("project-manager", "Approved", null, null).statusCode()).isEqualTo(204);
        poll(() -> activeNames(), active -> active.contains(COMPLETE_WORKPACKS), COMPLETE_WORKPACKS + " of " + instance);
        Timestamp approvedAt = jdbc.queryForObject("SELECT week_approved_at FROM bpa.progress_period WHERE id = ?", Timestamp.class, period);
        int audits = jdbc.queryForObject("SELECT count(*) FROM bpa.audit_log WHERE table_name = 'progress_period' AND record_id = ?", Integer.class, period);

        clock.set(TODAY.plusSeconds(3600));
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("progressPeriodId", period.toString());
        variables.put("weekEnding", W19.toString());
        variables.put("weekDecision", "Approved");
        variables.put("completedBy", "project-manager");
        long second = startAt(RECORD_DECISION, variables);

        // the second instance ran the record step if it has moved on to the next step (QA review: the first wait was already true and proved nothing)
        poll(() -> activeElements(second).stream().map(e -> e.path("elementId").asText()).toList(), active -> active.contains(COMPLETE_WORKPACKS), COMPLETE_WORKPACKS + " of the second instance " + second);
        assertThat(jdbc.queryForObject("SELECT week_approved_at FROM bpa.progress_period WHERE id = ?", Timestamp.class, period)).isEqualTo(approvedAt);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.audit_log WHERE table_name = 'progress_period' AND record_id = ?", Integer.class, period)).isEqualTo(audits);
    }

    /** Decision (c) of the story: once per week per CBS. BPA-65's cost alert is once per day, so a re-run on a later day must not send it again (plan Q3). */
    @Test
    void aSendBackOnALaterDaySendsNoSecondCostAlert() {
        toTheWeekTask(true);
        int cost = costAlertRows();
        assertThat(cost).as("the first pass sent the cost alert").isGreaterThan(0);
        assertThat(decide("project-manager", "ChangesRequested", "Progress", PUNCH_LIST).statusCode()).isEqualTo(204);
        String ppc = awaitTask(instance, COMPLETION);
        clock.set(TODAY.plusSeconds(24 * 3600)); // 21 Nov

        HttpResponse<String> approvedAgain = ppcApprove(ppc, "WP-000461", "95");
        assertThat(approvedAgain.statusCode()).as(approvedAgain.body()).isEqualTo(204);
        awaitTask(instance, REVIEW);

        assertThat(costAlertRows()).as("no second cost alert for the same week").isEqualTo(cost);
    }

    // --- helpers ----------------------------------------------------------------------------------------------------------------

    /**
     * The Background: PPC approved 40% and 100% for the week ending 19 Nov, the instance starts at the Earned man-hours step, and cost-controller completes the
     * review. With {@code alerts}, CBS-139-PIP-01 is over both limits (1200.00 approved hours against 1000.00; cost 300000.00 against 250000.00) and the review
     * carries the remark "Rework on spools".
     */
    private void toTheWeekTask(boolean alerts) {
        if (alerts) {
            fixture.line(sheets.e1021(), LocalDate.of(2026, 11, 10), "Cutting", new BigDecimal("1200.00"), "Approved");
            jdbc.update("UPDATE bpa.cbs_actual_cost SET actual_cost = 300000.00 WHERE cbs_code_ref = ? AND period_month = DATE '2026-11-01'", PIP01);
        }
        jdbc.update("""
                INSERT INTO bpa.progress_period (week_ending, cut_off_at, status, process_instance_key, created_by, updated_by)
                VALUES (?, ?, 'CutOff', 9000000019, 'test', 'test')""", java.sql.Date.valueOf(W19),
                Timestamp.from(W19.atTime(20, 59).toInstant(java.time.ZoneOffset.UTC)));
        period = jdbc.queryForObject("SELECT id FROM bpa.progress_period WHERE week_ending = ?", UUID.class, java.sql.Date.valueOf(W19));
        approved(period, "WP-000457", "40");
        approved(period, "WP-000461", "100");
        instance = startAt(EARNED, Map.of("progressPeriodId", period.toString(), "weekEnding", W19.toString()));
        jdbc.update("UPDATE bpa.progress_period SET process_instance_key = ? WHERE id = ?", instance, period);
        String review = awaitTask(instance, REVIEW);
        HttpResponse<String> reviewed = completeReview(review, alerts ? "Rework on spools" : "Checked");
        assertThat(reviewed.statusCode()).as(reviewed.body()).isEqualTo(204);
        weekTask = awaitTask(instance, APPROVE_WEEK);
    }

    private HttpResponse<String> completeReview(String task, String remark) {
        List<Map<String, Object>> reviews = List.of(Map.of("cbsCodeRef", PIP01, "remark", remark));
        return flow.loggedInAs("cost-controller").post("/api/v1/tasks/" + task + "/complete", toJson(Map.of("variables", Map.of("cbsReviews", reviews))));
    }

    /** The Project Manager's decision on the open UserTask_ApproveWeeklyProgress task; null target and reason are left out. */
    private HttpResponse<String> decide(String persona, String decision, String target, String reason) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("weekDecision", decision);
        if (target != null) {
            variables.put("weekChangeTarget", target);
        }
        if (reason != null) {
            variables.put("weekDecisionReason", reason);
        }
        return flow.loggedInAs(persona).post("/api/v1/tasks/" + weekTask + "/complete", toJson(Map.of("variables", variables)));
    }

    /** PPC approves the week again: every Work Pack at its pre-filled %, except {@code workpack}, which gets {@code pct} with a remark (BPA-60). */
    private HttpResponse<String> ppcApprove(String task, String workpack, String pct) {
        HttpResponse<String> week = flow.loggedInAs("ppc-team").get("/api/v1/progress-periods/2026-11-19/workpacks");
        assertThat(week.statusCode()).as(week.body()).isEqualTo(200);
        List<Map<String, Object>> approvals = new ArrayList<>();
        for (JsonNode row : json(week.body()).path("workpacks")) {
            Map<String, Object> approval = new LinkedHashMap<>();
            approval.put("workpackNumber", row.path("workpackNumber").asText());
            if (workpack.equals(row.path("workpackNumber").asText())) {
                approval.put("approvedPct", new BigDecimal(pct));
                approval.put("remark", "Punch list items open");
            } else {
                approval.put("approvedPct", row.path("approvedPct").decimalValue());
                approval.put("remark", row.path("remark").asText("Approved by PPC")); // the seeded approvals have no report row, so any % differs from the reference and needs a remark (BPA-60)
            }
            approvals.add(approval);
        }
        return flow.loggedInAs("ppc-team").post("/api/v1/tasks/" + task + "/complete", toJson(Map.of("variables", Map.of("completionApprovals", approvals))));
    }

    private long startAt(String elementId, Map<String, Object> variables) {
        Reply started = IntegrationApp.admin.post("/v2/process-instances", Map.of(
                "processDefinitionId", PROCESS, "variables", variables, "startInstructions", List.of(Map.of("elementId", elementId))));
        assertThat(started.status()).as("start at " + elementId + ": " + started.body()).isEqualTo(200);
        return Long.parseLong(started.body().path("processInstanceKey").asText());
    }

    private String awaitTask(long forInstance, String elementId) {
        return poll(() -> openTasks(forInstance, elementId).stream().findFirst(), found -> found.isPresent(), elementId + " of " + forInstance).orElseThrow()
                .path("userTaskKey").asText();
    }

    private List<String> activeNames() {
        return activeElements(instance).stream().map(e -> e.path("elementId").asText()).toList();
    }

    private void approved(UUID periodId, String workpack, String pct) {
        jdbc.update("""
                INSERT INTO bpa.workpack_progress (workpack_id, progress_period_id, approved_pct, approved_by, approved_at, created_by, updated_by)
                SELECT id, ?, ?::numeric, 'ppc-team', now(), 'test', 'test' FROM bpa.workpack WHERE workpack_number = ?""", periodId, pct, workpack);
    }

    private BigDecimal approvedPct(String workpack) {
        return jdbc.queryForObject("""
                SELECT p.approved_pct FROM bpa.workpack_progress p JOIN bpa.workpack w ON w.id = p.workpack_id WHERE w.workpack_number = ? AND p.progress_period_id = ?""",
                BigDecimal.class, workpack, period);
    }

    private BigDecimal earned(String activity) {
        return jdbc.queryForObject("SELECT earned_manhours FROM bpa.activity_progress WHERE activity_ref = ? AND progress_period_id = ?", BigDecimal.class, activity, period);
    }

    private int alertRows() {
        return jdbc.queryForObject("SELECT count(*) FROM bpa.variance_alert", Integer.class);
    }

    private int costAlertRows() {
        return jdbc.queryForObject("SELECT count(*) FROM bpa.variance_alert WHERE alert_type = 'Cost'", Integer.class);
    }

    private JsonNode cbsRow(String cbs) {
        HttpResponse<String> read = flow.loggedInAs("project-manager").get("/api/v1/progress-periods/2026-11-19/cbs-actuals");
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        for (JsonNode row : json(read.body()).path("cbs")) {
            if (cbs.equals(row.path("cbsCodeRef").asText())) {
                return row;
            }
        }
        throw new AssertionError(cbs + " is not a row of the week ending 19 Nov 2026");
    }
}
