package com.lcs.star.bpa;

import static com.lcs.star.bpa.CamundaSteps.completed;
import static com.lcs.star.bpa.CamundaSteps.json;
import static com.lcs.star.bpa.CamundaSteps.openTasks;
import static com.lcs.star.bpa.CamundaSteps.poll;
import static com.lcs.star.bpa.CamundaSteps.toJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.JsonNode;
import com.lcs.star.bpa.CamundaRest.Reply;
import com.lcs.star.bpa.adapter.CamundaProcessClient;
import com.lcs.star.bpa.adapter.CamundaTaskClient.EngineUnavailableException;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
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
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BPA-83 (E2): the Project Manager reopens a Locked week with a reason, only when {@code bpa.progress.allow-reopen} is true (default false). One test per Gherkin
 * scenario, named as in the plan (Jira gives no names; docs/04); the extras are ours (plan BPA-83). The certificate scenario (Epic 3) is not built and has no test
 * (decision 12 Nov 2026). Background: the business clock is Friday 20 Nov 2026 10:00 Asia/Riyadh; the week ending 19 Nov 2026 is Locked (WP-000457 at 40%, WP-000461 at 60%;
 * the last week, 12 Nov, is Locked with 30% and 60%); the setting is switched on per test through the environment ({@code ProgressSettings} reads it at each call).
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(AssignmentFileApiTest.TestClockConfig.class)
class WeekReopenApiTest {

    static final Instant TODAY = Instant.parse("2026-11-20T07:00:00Z"); // 10:00 in Riyadh
    static final String PROCESS = "Process_PPC_WeeklyProgressPeriod";
    static final String COMPLETION = "UserTask_ApproveCompletion";
    static final String EARNED = "ServiceTask_CalculateEarnedManhours";
    static final String REVIEW = "UserTask_ReviewActualManhours";
    static final String APPROVE_WEEK = "UserTask_ApproveWeeklyProgress";
    static final LocalDate W12 = LocalDate.of(2026, 11, 12);
    static final LocalDate W19 = LocalDate.of(2026, 11, 19);
    static final LocalDate W26 = LocalDate.of(2026, 11, 26);
    static final String REOPEN = "/api/v1/progress-periods/2026-11-19/reopen";
    static final String REASON = "FMS report double-counted WP-000457";
    static final String PIP01 = "CBS-139-PIP-01";
    static final String SETTING = "bpa.progress.allow-reopen";

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
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AssignmentFileApiTest.TestClock clock;

    @Autowired
    ConfigurableEnvironment environment;

    /** The real client, watched: "Camunda is never asked" is proved with no interactions, and the engine being down is a stub of the call. */
    @MockitoSpyBean
    CamundaProcessClient processes;

    private final Map<String, Object> overrides = new HashMap<>();
    private AssignmentFlow flow;
    private UUID period;

    /** All test classes share one Camunda: no weekly instance left waiting by another class may take this class's tasks. */
    @BeforeAll
    static void noInstanceLeftWaiting() {
        Mailpit.start();
        IntegrationApp.background();
        cancelWaiting();
    }

    private static void cancelWaiting() {
        for (String element : new String[] {"ServiceTask_OpenProgressPeriod", COMPLETION, "ServiceTask_RecordCompletionApproval", "ServiceTask_PrepareProgressCheck",
                "BusinessRuleTask_CheckProgressRule", "ServiceTask_RecordProgressResult", "UserTask_ApproveRetraction", "ServiceTask_RecordRetractionDecision", EARNED,
                "ServiceTask_PrepareVarianceCheck", "BusinessRuleTask_CheckManhourVariance", "BusinessRuleTask_CheckCostVariance", "ServiceTask_SendVarianceAlerts", REVIEW,
                "ServiceTask_RecordCbsReview", APPROVE_WEEK, "ServiceTask_RecordWeekDecision", "ServiceTask_CompleteWorkPacks", "ServiceTask_LockProgressPeriod"}) {
            CamundaSteps.cancelInstancesWaitingAt(element);
        }
    }

    @BeforeEach
    void background() {
        cancelWaiting();
        overrides.clear();
        if (environment.getPropertySources().get("bpa83-test") == null) {
            environment.getPropertySources().addFirst(new MapPropertySource("bpa83-test", overrides));
        }
        clock.set(TODAY);
        BookHoursFixture fixture = new BookHoursFixture(jdbc);
        BookHoursFixture.Ids ids = fixture.reset();
        fixture.completionBackground(); // WP-000457 and WP-000461 In Progress (A-2201)
        fixture.fmsBackground(ids); // CBS-139-PIP-01 on E1021 and E1099
        new RevisionFlow(port, jdbc).seedAppUsers();
        flow = new AssignmentFlow(port, jdbc);
        // the last locked week (12 Nov): approved 30 / 60
        jdbc.update("UPDATE bpa.progress_period SET status = 'Locked', locked_at = now() WHERE week_ending = ?", java.sql.Date.valueOf(W12));
        UUID locked = jdbc.queryForObject("SELECT id FROM bpa.progress_period WHERE week_ending = ?", UUID.class, java.sql.Date.valueOf(W12));
        approved(locked, "WP-000457", "30");
        approved(locked, "WP-000461", "60");
        // the week ending 19 Nov: Locked, approved 40 / 60 (nothing reaches 100%, so a second lock completes no Work Pack)
        jdbc.update("""
                INSERT INTO bpa.progress_period (week_ending, cut_off_at, status, locked_at, week_approved_by, week_approved_at, process_instance_key, created_by, updated_by)
                VALUES (?, ?, 'Locked', ?, 'project-manager', ?, 9000000019, 'test', 'test')""", java.sql.Date.valueOf(W19),
                Timestamp.from(Instant.parse("2026-11-19T20:59:00Z")), Timestamp.from(Instant.parse("2026-11-19T21:30:00Z")), Timestamp.from(Instant.parse("2026-11-19T21:30:00Z")));
        period = jdbc.queryForObject("SELECT id FROM bpa.progress_period WHERE week_ending = ?", UUID.class, java.sql.Date.valueOf(W19));
        approved(period, "WP-000457", "40");
        approved(period, "WP-000461", "60");
    }

    // --- the scenarios ----------------------------------------------------------------------------------------------------

    @Test
    void BPA83_pmReopensLockedWeekWithReason() {
        allowReopen();

        HttpResponse<String> response = flow.loggedInAs("project-manager").post(REOPEN, toJson(Map.of("reason", REASON)));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(json(response.body()).path("status").asText()).isEqualTo("Reopened");
        Map<String, Object> row = jdbc.queryForMap("SELECT status, reopened_by, reopened_at, reopen_reason FROM bpa.progress_period WHERE id = ?", period);
        assertThat(row.get("status")).isEqualTo("Reopened");
        assertThat(row.get("reopened_by")).isEqualTo("project-manager");
        assertThat((Timestamp) row.get("reopened_at")).isEqualTo(Timestamp.from(TODAY));
        assertThat(row.get("reopen_reason")).isEqualTo(REASON);
        // the audit log has one row with the reason, the user and the time
        List<Map<String, Object>> reasonRows = jdbc.queryForList(
                "SELECT new_value, username, changed_at FROM bpa.audit_log WHERE table_name = 'progress_period' AND record_id = ? AND column_name = 'reopen_reason'", period);
        assertThat(reasonRows).hasSize(1);
        assertThat(reasonRows.get(0).get("new_value")).isEqualTo(REASON);
        assertThat(reasonRows.get(0).get("username")).isEqualTo("project-manager");
        assertThat((Timestamp) reasonRows.get(0).get("changed_at")).isEqualTo(Timestamp.from(TODAY));
        // PPC Team can change the approved % for that week again: the new instance waits at Approve completion %
        String task = awaitTask(newInstance(), COMPLETION);
        HttpResponse<String> changed = ppcApprove(task, "WP-000457", "50");
        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(204);
        poll(() -> approvedPct("WP-000457"), pct -> pct.compareTo(new BigDecimal("50")) == 0, "the approved % of WP-000457 changed to 50");
    }

    @Test
    void BPA83_reasonIsRequired() {
        allowReopen();
        TestBrowserHolder pm = new TestBrowserHolder(flow);

        for (String body : new String[] {"{}", "{\"reason\":\"\"}", "{\"reason\":\"   \"}"}) {
            HttpResponse<String> response = pm.post(body);
            assertThat(response.statusCode()).as(body + " -> " + response.body()).isEqualTo(400);
            assertThat(json(response.body()).path("detail").asText()).isEqualTo("Enter a reason to reopen the week");
        }

        assertThat(statusOfW19()).as("the week stays Locked").isEqualTo("Locked");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.audit_log WHERE table_name = 'progress_period' AND record_id = ?", Integer.class, period)).isZero();
        verifyNoInteractions(processes); // Camunda is never asked
    }

    @Test
    void BPA83_reopenOffByDefault() {
        // the setting is not set: the default is false

        HttpResponse<String> response = flow.loggedInAs("project-manager").post(REOPEN, toJson(Map.of("reason", "Correction")));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
        assertThat(json(response.body()).path("detail").asText()).isEqualTo("Reopening a locked week is not allowed");
        assertThat(statusOfW19()).isEqualTo("Locked");
    }

    @Test
    void BPA83_otherPersonasCannotReopen() {
        allowReopen();

        HttpResponse<String> response = flow.loggedInAs("ppc-team").post(REOPEN, toJson(Map.of("reason", "Correction")));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
        assertThat(statusOfW19()).as("the week stays Locked").isEqualTo("Locked");
    }

    /** The whole path: reopen, PPC approves a new %, cost-controller reviews, the Project Manager approves the week, and it is Locked again. */
    @Test
    void BPA83_reopenedWeekLocksAgainThroughApproval() {
        allowReopen();
        assertThat(flow.loggedInAs("project-manager").post(REOPEN, toJson(Map.of("reason", REASON))).statusCode()).isEqualTo(200);
        long instance = newInstance();
        assertThat(ppcApprove(awaitTask(instance, COMPLETION), "WP-000457", "50").statusCode()).isEqualTo(204);

        String review = awaitTask(instance, REVIEW);
        HttpResponse<String> reviewed = flow.loggedInAs("cost-controller").post("/api/v1/tasks/" + review + "/complete",
                toJson(Map.of("variables", Map.of("cbsReviews", List.of(Map.of("cbsCodeRef", PIP01, "remark", "Checked"))))));
        assertThat(reviewed.statusCode()).as(reviewed.body()).isEqualTo(204);
        String weekTask = awaitTask(instance, APPROVE_WEEK);
        HttpResponse<String> approved = flow.loggedInAs("project-manager").post("/api/v1/tasks/" + weekTask + "/complete",
                toJson(Map.of("variables", Map.of("weekDecision", "Approved"))));
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(204);

        poll(() -> statusOfW19(), "Locked"::equals, "the week Locked again");
        poll(() -> completed(instance, "EndEvent_PeriodLocked", 1), ended -> ended, "the new instance ended at EndEvent_PeriodLocked");
        // Earned man-hours were recalculated once, in this pass: one row for the activity
        assertThat(completed(instance, EARNED, 1)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.activity_progress WHERE progress_period_id = ? AND activity_ref = 'A-2201'", Integer.class, period)).isEqualTo(1);
        // the approval is recorded afresh, and the second lock audits the first lock's time as its old value
        Map<String, Object> row = jdbc.queryForMap("SELECT week_approved_by, week_approved_at FROM bpa.progress_period WHERE id = ?", period);
        assertThat(row.get("week_approved_by")).isEqualTo("project-manager");
        assertThat((Timestamp) row.get("week_approved_at")).isEqualTo(Timestamp.from(TODAY));
        assertThat(jdbc.queryForObject("""
                SELECT old_value FROM bpa.audit_log WHERE table_name = 'progress_period' AND record_id = ? AND column_name = 'locked_at'
                ORDER BY changed_at DESC LIMIT 1""", String.class, period)).as("the old locked_at is audited, not null").isNotNull();
    }

    // --- not scenarios (plan BPA-83) -----------------------------------------------------------------------------------------

    /** ASSUMPTION (text): a week that is not Locked, a CutOff or an already Reopened one, is 409. */
    @Test
    void aWeekThatIsNotLockedCannotBeReopened() {
        allowReopen();
        jdbc.update("UPDATE bpa.progress_period SET status = 'CutOff', locked_at = NULL WHERE id = ?", period);

        HttpResponse<String> cutOff = flow.loggedInAs("project-manager").post(REOPEN, toJson(Map.of("reason", REASON)));

        assertThat(cutOff.statusCode()).as(cutOff.body()).isEqualTo(409);
        assertThat(json(cutOff.body()).path("detail").asText()).isEqualTo("Week ending 19 Nov 2026 is not locked");
        jdbc.update("UPDATE bpa.progress_period SET status = 'Locked', locked_at = now() WHERE id = ?", period);
        assertThat(flow.loggedInAs("project-manager").post(REOPEN, toJson(Map.of("reason", REASON))).statusCode()).isEqualTo(200);
        HttpResponse<String> again = flow.loggedInAs("project-manager").post(REOPEN, toJson(Map.of("reason", REASON)));
        assertThat(again.statusCode()).as(again.body()).isEqualTo(409);
        assertThat(json(again.body()).path("detail").asText()).isEqualTo("Week ending 19 Nov 2026 is not locked");
    }

    @Test
    void anUnknownWeekIsNotFound() {
        allowReopen();

        HttpResponse<String> response = flow.loggedInAs("project-manager").post("/api/v1/progress-periods/2026-12-31/reopen", toJson(Map.of("reason", REASON)));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
        assertThat(json(response.body()).path("detail").asText()).isEqualTo("Week not found");
    }

    /** BPA-60 (Q3): while week N is Reopened, week N+1's approved % is refused until N is Locked again. A guard: no code changes for it. */
    @Test
    void theNextWeekWaitsUntilTheReopenedWeekIsLockedAgain() {
        allowReopen();
        assertThat(flow.loggedInAs("project-manager").post(REOPEN, toJson(Map.of("reason", REASON))).statusCode()).isEqualTo(200);
        jdbc.update("""
                INSERT INTO bpa.progress_period (week_ending, cut_off_at, status, process_instance_key, created_by, updated_by)
                VALUES (?, ?, 'CutOff', 9000000026, 'test', 'test')""", java.sql.Date.valueOf(W26), Timestamp.from(Instant.parse("2026-11-26T20:59:00Z")));
        UUID next = jdbc.queryForObject("SELECT id FROM bpa.progress_period WHERE week_ending = ?", UUID.class, java.sql.Date.valueOf(W26));
        long instance = startAt(COMPLETION, Map.of("progressPeriodId", next.toString(), "weekEnding", W26.toString()));
        jdbc.update("UPDATE bpa.progress_period SET process_instance_key = ? WHERE id = ?", instance, next);

        HttpResponse<String> refused = flow.loggedInAs("ppc-team").post("/api/v1/tasks/" + awaitTask(instance, COMPLETION) + "/complete",
                toJson(Map.of("variables", Map.of("completionApprovals", List.of()))));

        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(json(refused.body()).path("detail").asText()).isEqualTo("Week ending 19 Nov 2026 must be locked first");
    }

    /** If the engine cannot be reached, nothing changes: 503 and the week stays Locked. */
    @Test
    void nothingChangesWhenTheEngineIsDown() throws Exception {
        allowReopen();
        doThrow(new EngineUnavailableException("Camunda cannot be reached")).when(processes).correlateForKey(anyString(), anyString(), anyMap());

        HttpResponse<String> response = flow.loggedInAs("project-manager").post(REOPEN, toJson(Map.of("reason", REASON)));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(503);
        assertThat(statusOfW19()).isEqualTo("Locked");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.audit_log WHERE table_name = 'progress_period' AND record_id = ?", Integer.class, period)).isZero();
    }

    // --- helpers ----------------------------------------------------------------------------------------------------------

    private void allowReopen() {
        overrides.put(SETTING, "true");
    }

    private String statusOfW19() {
        return jdbc.queryForObject("SELECT status FROM bpa.progress_period WHERE id = ?", String.class, period);
    }

    /** The instance the reopen started: the period's process_instance_key is the new instance (plan §3). */
    private long newInstance() {
        return poll(() -> jdbc.queryForObject("SELECT process_instance_key FROM bpa.progress_period WHERE id = ?", Long.class, period), key -> key != 9000000019L,
                "the period linked to the reopen's new instance");
    }

    private void approved(UUID periodId, String workpack, String pct) {
        jdbc.update("""
                INSERT INTO bpa.workpack_progress (workpack_id, progress_period_id, approved_pct, approved_by, approved_at, created_by, updated_by)
                SELECT id, ?, ?::numeric, 'ppc-team', now(), 'test', 'test' FROM bpa.workpack WHERE workpack_number = ?""", periodId, pct, workpack);
    }

    private BigDecimal approvedPct(String workpack) {
        return jdbc.queryForObject("""
                SELECT p.approved_pct FROM bpa.workpack_progress p JOIN bpa.workpack w ON w.id = p.workpack_id
                WHERE w.workpack_number = ? AND p.progress_period_id = ?""", BigDecimal.class, workpack, period);
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
                approval.put("remark", "Corrected after the reopen");
            } else {
                approval.put("approvedPct", row.path("approvedPct").decimalValue());
                approval.put("remark", row.path("remark").asText("Approved by PPC"));
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

    /** The project-manager session, posting a raw body to the reopen endpoint. */
    private static final class TestBrowserHolder {
        private final com.lcs.star.bpa.api.TestBrowser browser;

        TestBrowserHolder(AssignmentFlow flow) {
            this.browser = flow.loggedInAs("project-manager");
        }

        HttpResponse<String> post(String body) {
            return browser.post(REOPEN, body);
        }
    }
}
