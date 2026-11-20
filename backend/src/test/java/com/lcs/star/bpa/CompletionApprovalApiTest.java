package com.lcs.star.bpa;

import static com.lcs.star.bpa.CamundaSteps.activeElements;
import static com.lcs.star.bpa.CamundaSteps.json;
import static com.lcs.star.bpa.CamundaSteps.openTasks;
import static com.lcs.star.bpa.CamundaSteps.poll;
import static com.lcs.star.bpa.CamundaSteps.toJson;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.lcs.star.bpa.BookHoursFixture.FmsIds;
import com.lcs.star.bpa.CamundaRest.Reply;
import com.lcs.star.bpa.persistence.ProgressPeriodRepository;
import com.lcs.star.bpa.progress.CompletionApprovalService;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BPA-60 (E2-10): PPC approves the completion % of each Work Pack for the week. One test per Gherkin scenario, named as in the Jira Definition of Done.
 * Background: today is 20 Nov 2026 (Asia/Riyadh); the report for the week ending 19 Nov 2026 gave WP-000457 40% (Actual hours 150) and WP-000461 100%;
 * the last locked week (12 Nov, locked here by SQL: locking itself is BPA-67) holds WP-000457 30%, WP-000461 60%, WP-000470 0%; WP-000470 has no report
 * row; logged in as ppc-team. {@link BookHoursFixture} gives the three Work Packs (all In Progress; WP-000457 with phases of 500 h in all). The weekly
 * instance waits at UserTask_ApproveCompletion (started there with startInstructions, with a progress_period row that carries its key). The setting
 * progress.basis is changed through the environment, which the app reads at each call.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(AssignmentFileApiTest.TestClockConfig.class)
class CompletionApprovalApiTest {

    static final Instant TODAY = Instant.parse("2026-11-20T07:00:00Z"); // 10:00 in Riyadh
    static final String PROCESS = "Process_PPC_WeeklyProgressPeriod";
    static final String APPROVE = "UserTask_ApproveCompletion";
    static final String NEXT = "ServiceTask_PrepareProgressCheck";
    static final LocalDate W12 = LocalDate.of(2026, 11, 12);
    static final LocalDate W19 = LocalDate.of(2026, 11, 19);
    static final String REMARK_REQUIRED = "A remark is required when the approved % differs from the reported %";

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
    ConfigurableEnvironment environment;

    @Autowired
    CompletionApprovalService approvalService;

    private BookHoursFixture fixture;
    private BookHoursFixture.Ids ids;
    private FmsIds fms;
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
                NEXT}) {
            CamundaSteps.cancelInstancesWaitingAt(element);
        }
    }

    @BeforeEach
    void background() {
        cancelWaiting();
        clock.set(TODAY);
        fixture = new BookHoursFixture(jdbc);
        ids = fixture.reset();
        fixture.completionBackground(); // WP-000457 and WP-000461 In Progress
        fms = fixture.fmsBackground(ids); // WP-000470 In Progress
        flow = new AssignmentFlow(port, jdbc);
        // the last locked week (12 Nov): approved 30 / 60 / 0 (locking is BPA-67, so by SQL)
        jdbc.update("UPDATE bpa.progress_period SET status = 'Locked', locked_at = now() WHERE week_ending = ?", java.sql.Date.valueOf(W12));
        UUID locked = jdbc.queryForObject("SELECT id FROM bpa.progress_period WHERE week_ending = ?", UUID.class, java.sql.Date.valueOf(W12));
        approved(locked, "WP-000457", "30");
        approved(locked, "WP-000461", "60");
        approved(locked, "WP-000470", "0");
        // the week ending 19 Nov: the instance waits at the approval task; the accepted report gave 40% and 100%
        instance = startAtApprove();
        insertPeriod(W19, instance);
        period = jdbc.queryForObject("SELECT id FROM bpa.progress_period WHERE week_ending = ?", UUID.class, java.sql.Date.valueOf(W19));
        UUID report = jdbc.queryForObject("""
                INSERT INTO bpa.completion_report (progress_period_id, file_name, sha256, uploaded_by, received_at, outcome, created_by, updated_by)
                VALUES (?, 'fms-completion-wk47.xlsx', 'test', 'ppc-team', now(), 'Accepted', 'test', 'test') RETURNING id""", UUID.class, period);
        reportRow(report, 2, "WP-000457", "40", "150");
        reportRow(report, 3, "WP-000461", "100", "95");
        reported(period, "WP-000457", "40");
        reported(period, "WP-000461", "100");
        task = awaitTask(instance, APPROVE);
        progressSetting("basis", "Physical");
    }

    @AfterEach
    void removeSettings() {
        environment.getPropertySources().remove("bpa60");
    }

    // --- the scenarios ----------------------------------------------------------------------------------------------------

    @Test
    void BPA60_ppcApprovesReportedPercent() {
        HttpResponse<String> done = complete("ppc-team", preFilled());

        assertThat(done.statusCode()).as(done.body()).isEqualTo(204);
        awaitRecorded();
        assertThat(approvedPct("WP-000457", W19)).isEqualByComparingTo("40");
        assertThat(approvedPct("WP-000461", W19)).isEqualByComparingTo("100");
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT p.approved_by, p.approved_at FROM bpa.workpack_progress p JOIN bpa.workpack w ON w.id = p.workpack_id
                WHERE w.workpack_number = 'WP-000457' AND p.progress_period_id = ?""", period);
        assertThat(row.get("approved_by")).isEqualTo("ppc-team");
        assertThat(row.get("approved_at")).isNotNull();
    }

    @Test
    void BPA60_ppcChangesAPercentBeforeApproving() {
        List<Map<String, Object>> approvals = preFilled();
        change(approvals, "WP-000457", "38", "FMS double-counted cutting");

        HttpResponse<String> done = complete("ppc-team", approvals);

        assertThat(done.statusCode()).as(done.body()).isEqualTo(204);
        awaitRecorded();
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT p.reported_pct, p.approved_pct, p.override_remark FROM bpa.workpack_progress p JOIN bpa.workpack w ON w.id = p.workpack_id
                WHERE w.workpack_number = 'WP-000457' AND p.progress_period_id = ?""", period);
        assertThat((BigDecimal) row.get("reported_pct")).isEqualByComparingTo("40");
        assertThat((BigDecimal) row.get("approved_pct")).isEqualByComparingTo("38");
        assertThat(row.get("override_remark")).isEqualTo("FMS double-counted cutting");
    }

    @Test
    void BPA60_missingReportRowKeepsLastWeek() {
        JsonNode row = rowOf("WP-000470", read("ppc-team"));

        assertThat(row.path("noReportRow").asBoolean()).isTrue();
        assertThat(row.path("approvedPct").decimalValue()).isEqualByComparingTo("0");
        assertThat(row.path("lastLockedPct").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void BPA60_hoursBasedPercentShownForInformation() {
        fixture.fill(fms.e1021(), "Cutting", new BigDecimal("150"), "Approved"); // 150 approved hours of the 500 estimated

        JsonNode row = rowOf("WP-000457", read("ppc-team"));

        assertThat(row.path("hoursBasedPct").decimalValue()).isEqualByComparingTo("30");
        assertThat(row.path("reportedPct").decimalValue()).isEqualByComparingTo("40");
        assertThat(row.path("approvedPct").decimalValue()).as("the default stays the reported %").isEqualByComparingTo("40");
    }

    @Test
    void BPA60_percentTwoDecimalsOnly() {
        List<Map<String, Object>> approvals = preFilled();
        change(approvals, "WP-000457", "38.125", "Rounded by hand");

        HttpResponse<String> refused = complete("ppc-team", approvals);

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(json(refused.body()).path("detail").asText()).isEqualTo("Completion % can have at most 2 decimal places");
        assertThat(openTasks(instance, APPROVE)).as("the task stays open").hasSize(1);
    }

    @Test
    void BPA60_otherPersonasCannotApproveCompletion() {
        HttpResponse<String> refused = complete("cost-controller", preFilled());

        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(openTasks(instance, APPROVE)).hasSize(1);
    }

    @Test
    void BPA60_fmsActualHoursShownBesideApprovedHours() {
        fixture.fill(fms.e1021(), "Cutting", new BigDecimal("142.5"), "Approved");

        JsonNode row = rowOf("WP-000457", read("ppc-team"));

        assertThat(row.path("fmsActualHours").decimalValue()).isEqualByComparingTo("150.00");
        assertThat(row.path("approvedHours").decimalValue()).isEqualByComparingTo("142.50");
        assertThat(row.path("hoursDifference").decimalValue()).isEqualByComparingTo("7.50");
        assertThat(complete("ppc-team", preFilled()).statusCode()).as("nothing blocks approval").isEqualTo(204);
    }

    @Test
    void BPA60_changedPercentNeedsARemark() {
        List<Map<String, Object>> approvals = preFilled();
        change(approvals, "WP-000457", "38", "");

        HttpResponse<String> refused = complete("ppc-team", approvals);

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(json(refused.body()).path("detail").asText()).isEqualTo(REMARK_REQUIRED);
    }

    @Test
    void BPA60_previousWeekMustBeLockedFirst() {
        jdbc.update("UPDATE bpa.progress_period SET status = 'CutOff', locked_at = NULL WHERE week_ending = ?", java.sql.Date.valueOf(W12));

        HttpResponse<String> refused = complete("ppc-team", preFilled());

        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(json(refused.body()).path("detail").asText()).isEqualTo("Week ending 12 Nov 2026 must be locked first");
        assertThat(openTasks(instance, APPROVE)).as("the task stays open").hasSize(1);
    }

    @Test
    void BPA60_withBasisHoursTheApprovedIsPreFilledFromLoggedHours() {
        progressSetting("basis", "Hours");
        jdbc.update("""
                UPDATE bpa.workpack_phase SET manhours = CASE phase WHEN 'Cutting' THEN 100 ELSE 50 END
                WHERE workpack_id = ?""", ids.workpackId()); // a budget of 150
        fixture.fill(fms.e1021(), "Cutting", new BigDecimal("60"), "Approved");
        // QA review: Jira's own numbers make the reported % (40) equal the hours-based % (60 of 150), so the test could not tell the bases apart; the reported
        // % is 35 here, and 40.00 can then only come from the hours
        jdbc.update("""
                UPDATE bpa.workpack_progress SET reported_pct = 35 WHERE progress_period_id = ?
                AND workpack_id = (SELECT id FROM bpa.workpack WHERE workpack_number = 'WP-000457')""", period);

        JsonNode row = rowOf("WP-000457", read("ppc-team"));

        assertThat(row.path("approvedPct").decimalValue()).isEqualByComparingTo("40.00");
        assertThat(row.path("reportedPct").decimalValue()).isEqualByComparingTo("35.00");
    }

    @Test
    void BPA60_withBasisPhysicalTheReportedPhysicalIsPreFilled() {
        progressSetting("basis", "Physical");
        jdbc.update("""
                UPDATE bpa.workpack_progress SET reported_pct = 35 WHERE progress_period_id = ?
                AND workpack_id = (SELECT id FROM bpa.workpack WHERE workpack_number = 'WP-000457')""", period);

        JsonNode row = rowOf("WP-000457", read("ppc-team"));

        assertThat(row.path("approvedPct").decimalValue()).isEqualByComparingTo("35.00");
    }

    @Test
    void BPA60_theReportedByProductionIsShownForInformation() {
        UUID line = fixture.line(fms.e1021(), LocalDate.of(2026, 11, 16), "Cutting", new BigDecimal("8"), "Submitted");
        jdbc.update("UPDATE bpa.digital_timesheet_line SET reported_completion_pct = 40 WHERE id = ?", line);

        JsonNode row = rowOf("WP-000457", read("ppc-team"));

        assertThat(row.path("reportedByProductionPct").decimalValue()).isEqualByComparingTo("40.00");
    }

    // --- extras (not Gherkin scenarios) ---------------------------------------------------------------------------------------

    @Test
    void theWeekIsAllOrNothingEveryWorkPackMustBeCovered() {
        List<Map<String, Object>> approvals = preFilled();
        approvals.removeIf(a -> "WP-000461".equals(a.get("workpackNumber")));

        HttpResponse<String> refused = complete("ppc-team", approvals);

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(json(refused.body()).path("detail").asText()).isEqualTo("Approvals must cover every Work Pack of the week");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.workpack_progress WHERE approved_pct IS NOT NULL AND progress_period_id = ?", Integer.class, period))
                .isZero();
    }

    @Test
    void aPercentAbove100IsRefused() {
        List<Map<String, Object>> approvals = preFilled();
        change(approvals, "WP-000461", "120", "Reported over 100");

        HttpResponse<String> refused = complete("ppc-team", approvals);

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(json(refused.body()).path("detail").asText()).isEqualTo("Completion % must be between 0 and 100");
    }

    @Test
    void aRemarkOf251CharactersIsRefused() {
        List<Map<String, Object>> approvals = preFilled();
        change(approvals, "WP-000457", "38", "x".repeat(251));

        HttpResponse<String> refused = complete("ppc-team", approvals);

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(json(refused.body()).path("detail").asText()).isEqualTo("Remark can be at most 250 characters");
    }

    @Test
    void aNoReportRowWorkPackNeedsARemarkToChange() {
        List<Map<String, Object>> approvals = preFilled();
        change(approvals, "WP-000470", "5", "");

        HttpResponse<String> refused = complete("ppc-team", approvals);

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(json(refused.body()).path("detail").asText()).isEqualTo(REMARK_REQUIRED);
    }

    @Test
    void theFirstWeekHasNoLockRule() {
        jdbc.update("DELETE FROM bpa.workpack_progress WHERE progress_period_id = (SELECT id FROM bpa.progress_period WHERE week_ending = ?)", java.sql.Date.valueOf(W12));
        jdbc.update("DELETE FROM bpa.progress_period WHERE week_ending = ?", java.sql.Date.valueOf(W12));

        HttpResponse<String> done = complete("ppc-team", preFilled());

        assertThat(done.statusCode()).as("no earlier period: the rule is skipped").isEqualTo(204);
    }

    @Test
    void projectManagerReadsTheWeekAndOthersCannot() {
        assertThat(flow.loggedInAs("project-manager").get("/api/v1/progress-periods/2026-11-19/workpacks").statusCode()).isEqualTo(200);
        assertThat(flow.loggedInAs("supervisor").get("/api/v1/progress-periods/2026-11-19/workpacks").statusCode()).isEqualTo(403);
        assertThat(flow.loggedInAs("ppc-team").get("/api/v1/progress-periods/2026-11-26/workpacks").statusCode()).as("no such week").isEqualTo(404);
    }

    @Test
    void aReturnToTheTaskPreFillsTheStoredApproval() {
        jdbc.update("""
                UPDATE bpa.workpack_progress SET approved_pct = 38, override_remark = 'FMS double-counted cutting' WHERE progress_period_id = ?
                AND workpack_id = (SELECT id FROM bpa.workpack WHERE workpack_number = 'WP-000457')""", period);

        JsonNode row = rowOf("WP-000457", read("ppc-team"));

        assertThat(row.path("approvedPct").decimalValue()).isEqualByComparingTo("38");
        assertThat(row.path("remark").asText()).isEqualTo("FMS double-counted cutting");
    }

    /**
     * QA review, plan question for Jira: under the Hours basis the pre-fill (the hours-based %) differs from the reported %, and Jira's rule (a) asks for a
     * remark whenever the approved % differs from the reported %. The code follows (a) literally, so an untouched pre-fill that differs from the reported % needs
     * a remark. This pins that reading (an ASSUMPTION) so a change to it is deliberate.
     */
    @Test
    void underTheHoursBasisAnUntouchedPreFillThatDiffersFromTheReportedPercentNeedsARemark() {
        progressSetting("basis", "Hours");
        jdbc.update("UPDATE bpa.workpack_phase SET manhours = CASE phase WHEN 'Cutting' THEN 100 ELSE 50 END WHERE workpack_id = ?", ids.workpackId());
        fixture.fill(fms.e1021(), "Cutting", new BigDecimal("60"), "Approved");
        jdbc.update("""
                UPDATE bpa.workpack_progress SET reported_pct = 35 WHERE progress_period_id = ?
                AND workpack_id = (SELECT id FROM bpa.workpack WHERE workpack_number = 'WP-000457')""", period);

        HttpResponse<String> refused = complete("ppc-team", preFilled());

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(json(refused.body()).path("detail").asText()).isEqualTo(REMARK_REQUIRED);
    }

    /** QA review: rows of a Locked week never change (data model 13.3.4, worker-checked); a stale instance cannot overwrite them. */
    @Test
    void aLockedWeekIsNeverChanged() {
        jdbc.update("UPDATE bpa.progress_period SET status = 'Locked', locked_at = now() WHERE id = ?", period);
        List<CompletionApprovalService.Approval> approvals = CompletionApprovalService.parse(preFilled());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> approvalService.record(new ProgressPeriodRepository.Period(period, W19), approvals, "ppc-team"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Week ending 19 Nov 2026 is locked");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.workpack_progress WHERE approved_pct IS NOT NULL AND progress_period_id = ?", Integer.class, period))
                .isZero();
    }

    /** Re-run key (ADR 0007, D65): a retried record step finds the same values and writes nothing: approved_at is kept and nothing is audited twice. */
    @Test
    void aRerunOfRecordCompletionApprovalChangesNothing() {
        List<CompletionApprovalService.Approval> approvals = CompletionApprovalService.parse(preFilled());
        ProgressPeriodRepository.Period week = new ProgressPeriodRepository.Period(period, W19);

        approvalService.record(week, approvals, "ppc-team");
        java.sql.Timestamp first = approvedAt("WP-000457");
        approvalService.record(week, approvals, "ppc-team");

        assertThat(approvedAt("WP-000457")).as("approved_at is kept").isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.audit_log WHERE table_name = 'workpack_progress' AND column_name = 'approved_pct'", Integer.class))
                .as("one audit row per Work Pack, written once").isEqualTo(3);
    }

    // --- helpers ----------------------------------------------------------------------------------------------------------------

    private java.sql.Timestamp approvedAt(String workpack) {
        return jdbc.queryForObject("""
                SELECT p.approved_at FROM bpa.workpack_progress p JOIN bpa.workpack w ON w.id = p.workpack_id WHERE w.workpack_number = ? AND p.progress_period_id = ?""",
                java.sql.Timestamp.class, workpack, period);
    }

    /** The record step runs after the task completes: wait until the instance has moved on to BPA-61's step, then the rows are written. */
    private void awaitRecorded() {
        poll(() -> activeElements(instance).stream().map(e -> e.path("elementId").asText()).toList(), active -> active.contains(NEXT), NEXT + " of " + instance);
    }

    private long startAtApprove() {
        Reply started = IntegrationApp.admin.post("/v2/process-instances", Map.of(
                "processDefinitionId", PROCESS, "startInstructions", List.of(Map.of("elementId", APPROVE))));
        assertThat(started.status()).as("start at " + APPROVE + ": " + started.body()).isEqualTo(200);
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

    private void reportRow(UUID report, int rowNumber, String workpack, String pct, String hours) {
        jdbc.update("""
                INSERT INTO bpa.completion_report_row (completion_report_id, row_number, workpack_number, completion_pct_raw, actual_hours_raw, source_raw, source,
                                                       outcome, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'FMS', 'FMS', 'Read', 'test', 'test')""", report, rowNumber, workpack, pct, hours);
    }

    private BigDecimal approvedPct(String workpack, LocalDate weekEnding) {
        return jdbc.queryForObject("""
                SELECT p.approved_pct FROM bpa.workpack_progress p JOIN bpa.workpack w ON w.id = p.workpack_id JOIN bpa.progress_period d ON d.id = p.progress_period_id
                WHERE w.workpack_number = ? AND d.week_ending = ?""", BigDecimal.class, workpack, java.sql.Date.valueOf(weekEnding));
    }

    private JsonNode read(String user) {
        HttpResponse<String> response = flow.loggedInAs(user).get("/api/v1/progress-periods/2026-11-19/workpacks");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json(response.body());
    }

    private static JsonNode rowOf(String workpack, JsonNode week) {
        for (JsonNode row : week.path("workpacks")) {
            if (workpack.equals(row.path("workpackNumber").asText())) {
                return row;
            }
        }
        throw new AssertionError("no row for " + workpack + " in " + week);
    }

    /** What the screen sends when PPC changes nothing: every Work Pack of the week with its pre-filled approved %. */
    private List<Map<String, Object>> preFilled() {
        List<Map<String, Object>> approvals = new ArrayList<>();
        for (JsonNode row : read("ppc-team").path("workpacks")) {
            Map<String, Object> approval = new LinkedHashMap<>();
            approval.put("workpackNumber", row.path("workpackNumber").asText());
            approval.put("approvedPct", row.path("approvedPct").decimalValue());
            approvals.add(approval);
        }
        return approvals;
    }

    private static void change(List<Map<String, Object>> approvals, String workpack, String pct, String remark) {
        for (Map<String, Object> approval : approvals) {
            if (workpack.equals(approval.get("workpackNumber"))) {
                approval.put("approvedPct", new BigDecimal(pct));
                approval.put("remark", remark);
            }
        }
    }

    private HttpResponse<String> complete(String user, List<Map<String, Object>> approvals) {
        return flow.loggedInAs(user).post("/api/v1/tasks/" + task + "/complete", toJson(Map.of("variables", Map.of("completionApprovals", approvals))));
    }

    private void progressSetting(String name, String value) {
        environment.getPropertySources().remove("bpa60");
        environment.getPropertySources().addFirst(new MapPropertySource("bpa60", Map.of("bpa.progress." + name, value)));
    }
}
