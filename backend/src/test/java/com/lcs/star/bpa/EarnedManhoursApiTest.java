package com.lcs.star.bpa;

import static com.lcs.star.bpa.CamundaSteps.activeElements;
import static com.lcs.star.bpa.CamundaSteps.assertStaysEmpty;
import static com.lcs.star.bpa.CamundaSteps.json;
import static com.lcs.star.bpa.CamundaSteps.poll;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.lcs.star.bpa.CamundaRest.Reply;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BPA-62 (E2-13): the System calculates each activity's progress % and Earned man-hours for the week. One test per Gherkin scenario, named as in the Jira
 * Definition of Done. Background: today is 20 Nov 2026 (Asia/Riyadh); activity A-2201 has budgeted units 1200; WP-000457 (500 h) and WP-000461 (100 h) belong to
 * A-2201, both In Progress; WP-000470 (100 h) belongs to A-2299 (no budgeted units). Every scenario seeds the weeks by SQL (the 12 Nov week is Locked, locking is
 * BPA-67) and starts an instance at ServiceTask_CalculateEarnedManhours (plan Q8, an ASSUMPTION), so the real worker runs on the seeded rows; the test then waits
 * for the instance to reach ServiceTask_PrepareVarianceCheck (BPA-64, no worker yet).
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(AssignmentFileApiTest.TestClockConfig.class)
class EarnedManhoursApiTest {

    static final Instant TODAY = Instant.parse("2026-11-20T07:00:00Z"); // 10:00 in Riyadh
    static final String PROCESS = "Process_PPC_WeeklyProgressPeriod";
    static final String EARNED = "ServiceTask_CalculateEarnedManhours";
    static final String VARIANCE = "ServiceTask_PrepareVarianceCheck";
    static final String REVIEW = "UserTask_ReviewActualManhours"; // BPA-64: the variance worker now runs, so the instance moves on to the Cost Controller's review
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
    private UUID locked;
    private UUID week;

    /** All test classes share one Camunda: no weekly instance left waiting by another class may take this class's jobs. */
    @BeforeAll
    static void noInstanceLeftWaiting() {
        IntegrationApp.background();
        cancelWaiting();
    }

    private static void cancelWaiting() {
        for (String element : new String[] {"UserTask_ApproveCompletion", "ServiceTask_RecordCompletionApproval", "ServiceTask_PrepareProgressCheck",
                "BusinessRuleTask_CheckProgressRule", "ServiceTask_RecordProgressResult", "UserTask_ApproveRetraction", EARNED, VARIANCE, "BusinessRuleTask_CheckManhourVariance", "BusinessRuleTask_CheckCostVariance", "ServiceTask_SendVarianceAlerts", REVIEW, "ServiceTask_RecordCbsReview", "UserTask_ApproveWeeklyProgress"}) {
            CamundaSteps.cancelInstancesWaitingAt(element);
        }
    }

    @BeforeEach
    void background() {
        cancelWaiting();
        clock.set(TODAY);
        BookHoursFixture fixture = new BookHoursFixture(jdbc);
        BookHoursFixture.Ids ids = fixture.reset();
        fixture.completionBackground(); // WP-000457 and WP-000461 In Progress (and WP-000480, which the Background here does not have)
        fixture.fmsBackground(ids); // WP-000470 In Progress, activity A-2299
        jdbc.update("UPDATE bpa.workpack SET status = 'Draft' WHERE workpack_number = 'WP-000480'");
        jdbc.update("UPDATE bpa.workpack SET status = 'In Progress' WHERE workpack_number IN ('WP-000457', 'WP-000461', 'WP-000470')");
        flow = new AssignmentFlow(port, jdbc);
        // the fixture already has the 12 Nov period (a CutOff one): it is the last locked week here (locking is BPA-67)
        jdbc.update("UPDATE bpa.progress_period SET status = 'Locked', locked_at = now() WHERE week_ending = ?", java.sql.Date.valueOf(W12));
        locked = jdbc.queryForObject("SELECT id FROM bpa.progress_period WHERE week_ending = ?", UUID.class, java.sql.Date.valueOf(W12));
        week = insertPeriod(W19, "CutOff", 9000000019L);
    }

    /** The seed's A-2201 budget comes back after a test that changed it. */
    @AfterEach
    void restoreBudget() {
        jdbc.update("UPDATE bpa.activity SET budgeted_units = 1200.00 WHERE activity_ref = 'A-2201'");
    }

    // --- the scenarios ----------------------------------------------------------------------------------------------------

    @Test
    void BPA62_rollUpIsHoursWeighted() {
        approved(week, "WP-000457", "40");
        approved(week, "WP-000461", "100");

        run();

        assertRow("A-2201", "50.00", "1200.00", "600.00");
    }

    @Test
    void BPA62_earnedManhoursFromOneWorkPack() {
        setStatus("WP-000461", "Draft");
        approved(week, "WP-000457", "40");

        run();

        assertRow("A-2201", "40.00", "1200.00", "480.00");
    }

    @Test
    void BPA62_noBudgetedUnitsGivesNoEarned() {
        approved(week, "WP-000470", "10");

        run();

        Map<String, Object> row = row("A-2299");
        assertThat((BigDecimal) row.get("actual_pct")).isEqualByComparingTo("10.00");
        assertThat(row.get("budgeted_units")).isNull();
        assertThat(row.get("earned_manhours")).isNull();
    }

    @Test
    void BPA62_roundingHalfUp() {
        jdbc.update("UPDATE bpa.activity SET budgeted_units = 1000.01 WHERE activity_ref = 'A-2201'");
        setStatus("WP-000461", "Draft");
        approved(week, "WP-000457", "33.33");

        run();

        assertRow("A-2201", "33.33", "1000.01", "333.30");
    }

    @Test
    void BPA62_lockedWeekIsNotRecalculated() {
        Timestamp stamp = Timestamp.valueOf("2026-11-13 09:00:00");
        jdbc.update("""
                INSERT INTO bpa.activity_progress (activity_ref, progress_period_id, actual_pct, budgeted_units, earned_manhours, created_at, created_by, updated_at, updated_by)
                VALUES ('A-2201', ?, 30.00, 1200.00, 360.00, ?, 'test', ?, 'test')""", locked, stamp, stamp);
        approved(week, "WP-000457", "40");
        approved(week, "WP-000461", "100");

        run();

        assertRow("A-2201", "50.00", "1200.00", "600.00"); // the week that was calculated
        Map<String, Object> old = jdbc.queryForMap("SELECT earned_manhours, updated_at FROM bpa.activity_progress WHERE activity_ref = 'A-2201' AND progress_period_id = ?", locked);
        assertThat((BigDecimal) old.get("earned_manhours")).isEqualByComparingTo("360.00");
        assertThat((Timestamp) old.get("updated_at")).isEqualTo(stamp);
    }

    @Test
    void BPA62_completedWorkPackKeepsCountingAt100() {
        setStatus("WP-000461", "Completed");
        approved(locked, "WP-000461", "100"); // locked at 100% for the week ending 12 Nov
        approved(week, "WP-000457", "40");

        run();

        assertRow("A-2201", "50.00", "1200.00", "600.00");
    }

    @Test
    void BPA62_notStartedWorkPackCountsAtZero() {
        setStatus("WP-000461", "Released"); // no locked or approved %
        approved(week, "WP-000457", "40");

        run();

        assertRow("A-2201", "33.33", "1200.00", "400.00");
    }

    @Test
    void BPA62_weeklyIncreaseIsShown() {
        jdbc.update("""
                INSERT INTO bpa.activity_progress (activity_ref, progress_period_id, actual_pct, budgeted_units, earned_manhours, created_by, updated_by)
                VALUES ('A-2201', ?, 30.00, 1200.00, 360.00, 'test', 'test')""", locked);
        approved(week, "WP-000457", "40");
        approved(week, "WP-000461", "100");
        run();

        HttpResponse<String> response = flow.loggedInAs("ppc-team").get("/api/v1/progress-periods/2026-11-19/earned-manhours");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode a2201 = activity(json(response.body()), "A-2201");
        assertThat(a2201.path("earnedManhours").decimalValue()).isEqualByComparingTo("600.00");
        assertThat(a2201.path("lastLockedEarnedManhours").decimalValue()).isEqualByComparingTo("360.00");
        assertThat(a2201.path("weekIncrease").decimalValue()).isEqualByComparingTo("240.00");
    }

    // --- extras (not Gherkin scenarios) ---------------------------------------------------------------------------------------

    @Test
    void aRerunGivesTheSameRows() {
        approved(week, "WP-000457", "40");
        approved(week, "WP-000461", "100");
        run();
        Map<String, Object> first = row("A-2201");
        int audits = audits();

        run();

        assertThat(row("A-2201")).isEqualTo(first);
        assertThat(audits()).as("no new audit rows").isEqualTo(audits);
    }

    @Test
    void aSentBackWeekIsRecalculated() {
        approved(week, "WP-000457", "40");
        approved(week, "WP-000461", "100");
        run();
        int audits = audits();
        jdbc.update("""
                UPDATE bpa.workpack_progress SET approved_pct = 20 WHERE progress_period_id = ? AND workpack_id = (SELECT id FROM bpa.workpack WHERE workpack_number = 'WP-000457')""", week);

        run();

        assertRow("A-2201", "33.33", "1200.00", "400.00"); // (20 x 500 + 100 x 100) / 600
        assertThat(audits()).as("the changed columns are audited").isGreaterThan(audits);
    }

    @Test
    void aLockedWeekIsRefused() {
        approved(week, "WP-000457", "40");
        jdbc.update("UPDATE bpa.progress_period SET status = 'Locked', locked_at = now() WHERE id = ?", week);

        startAtEarned();

        assertStaysEmpty(() -> jdbc.queryForList("SELECT 1 FROM bpa.activity_progress WHERE progress_period_id = ?", week), "an activity_progress row of the Locked week");
    }

    @Test
    void anActivityWithNoCountedWorkPackHasNoRow() {
        approved(week, "WP-000457", "40");
        run();
        assertThat(jdbc.queryForList("SELECT 1 FROM bpa.activity_progress WHERE activity_ref = 'A-2301'")).as("A-2301 has no Work Pack").isEmpty();
        setStatus("WP-000457", "Draft");
        setStatus("WP-000461", "Draft");

        run();

        assertThat(jdbc.queryForList("SELECT 1 FROM bpa.activity_progress WHERE activity_ref = 'A-2201' AND progress_period_id = ?", week)).isEmpty();
    }

    @Test
    void earnedManhoursEndpointIsForPpcCostControllerAndProjectManager() {
        assertThat(flow.loggedInAs("ppc-team").get("/api/v1/progress-periods/2026-11-19/earned-manhours").statusCode()).isEqualTo(200);
        assertThat(flow.loggedInAs("cost-controller").get("/api/v1/progress-periods/2026-11-19/earned-manhours").statusCode()).isEqualTo(200);
        // BPA-66: the Project Manager reads Earned man-hours for the Approve weekly progress screen (was 403 in BPA-62)
        assertThat(flow.loggedInAs("project-manager").get("/api/v1/progress-periods/2026-11-19/earned-manhours").statusCode()).isEqualTo(200);
        assertThat(flow.loggedInAs("supervisor").get("/api/v1/progress-periods/2026-11-19/earned-manhours").statusCode()).isEqualTo(403);
        assertThat(flow.loggedInAs("cost-controller").get("/api/v1/progress-periods/2026-11-19/workpacks").statusCode()).as("BPA-60's screen stays PPC and PM").isEqualTo(403);
    }

    @Test
    void unknownWeekIs404() {
        HttpResponse<String> response = flow.loggedInAs("ppc-team").get("/api/v1/progress-periods/2026-10-01/earned-manhours");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json(response.body()).path("detail").asText()).isEqualTo("Week not found");
    }

    // --- helpers ----------------------------------------------------------------------------------------------------------------

    private UUID insertPeriod(LocalDate weekEnding, String status, long processInstanceKey) {
        return jdbc.queryForObject("""
                INSERT INTO bpa.progress_period (week_ending, cut_off_at, status, locked_at, process_instance_key, created_by, updated_by)
                VALUES (?, ?, ?, CASE WHEN ? = 'Locked' THEN now() END, ?, 'test', 'test') RETURNING id""", UUID.class, java.sql.Date.valueOf(weekEnding),
                Timestamp.from(weekEnding.atTime(20, 59).toInstant(java.time.ZoneOffset.UTC)), status, status, processInstanceKey);
    }

    private void approved(UUID periodId, String workpack, String pct) {
        jdbc.update("""
                INSERT INTO bpa.workpack_progress (workpack_id, progress_period_id, approved_pct, approved_by, approved_at, created_by, updated_by)
                SELECT id, ?, ?::numeric, 'test', now(), 'test', 'test' FROM bpa.workpack WHERE workpack_number = ?""", periodId, pct, workpack);
    }

    private void setStatus(String workpack, String status) {
        jdbc.update("UPDATE bpa.workpack SET status = ? WHERE workpack_number = ?", status, workpack);
    }

    /** An instance started at the calculation task for the week ending 19 Nov (its period is found from the variable, as BPA-61). */
    private long startAtEarned() {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("progressPeriodId", week.toString());
        variables.put("weekEnding", W19.toString());
        Reply started = IntegrationApp.admin.post("/v2/process-instances", Map.of(
                "processDefinitionId", PROCESS, "variables", variables, "startInstructions", List.of(Map.of("elementId", EARNED))));
        assertThat(started.status()).as("start at " + EARNED + ": " + started.body()).isEqualTo(200);
        return Long.parseLong(started.body().path("processInstanceKey").asText());
    }

    /** Runs the calculation for the week and waits until the instance has moved on to BPA-64's step. */
    private void run() {
        long instance = startAtEarned();
        poll(() -> activeElements(instance).stream().map(e -> e.path("elementId").asText()).toList(), active -> active.contains(VARIANCE) || active.contains(REVIEW), VARIANCE + " or " + REVIEW + " of " + instance);
    }

    private Map<String, Object> row(String activity) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT actual_pct, budgeted_units, earned_manhours, updated_at FROM bpa.activity_progress WHERE activity_ref = ? AND progress_period_id = ?""", activity, week);
        assertThat(rows).as("activity_progress row of " + activity + " for the week ending 19 Nov").hasSize(1);
        return rows.get(0);
    }

    private void assertRow(String activity, String actual, String budget, String earned) {
        Map<String, Object> row = row(activity);
        assertThat((BigDecimal) row.get("actual_pct")).isEqualByComparingTo(actual);
        assertThat((BigDecimal) row.get("budgeted_units")).isEqualByComparingTo(budget);
        assertThat((BigDecimal) row.get("earned_manhours")).isEqualByComparingTo(earned);
    }

    private int audits() {
        return jdbc.queryForObject("SELECT count(*) FROM bpa.audit_log WHERE table_name = 'activity_progress'", Integer.class);
    }

    private static JsonNode activity(JsonNode week, String activityRef) {
        for (JsonNode activity : week.path("activities")) {
            if (activityRef.equals(activity.path("activityRef").asText())) {
                return activity;
            }
        }
        throw new AssertionError("no activity " + activityRef + " in " + week);
    }
}
