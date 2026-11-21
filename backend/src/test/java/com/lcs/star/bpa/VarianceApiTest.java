package com.lcs.star.bpa;

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
 * BPA-64 (E2-14): the Cost Controller reviews the actual man-hours by CBS against the Current Forecast and is alerted above 15%. One test per Gherkin scenario,
 * named as in the Jira Definition of Done (the month lock and snapshot scenarios 12 and 13 are in {@link MonthSnapshotTest}). Background: today is 20 Nov 2026
 * (Asia/Riyadh); CBS-139-PIP-01 has Current Forecast 1000.00 MH (seed); the instance for the week ending 19 Nov 2026 starts at ServiceTask_PrepareVarianceCheck,
 * its period found from the progressPeriodId variable as in BPA-61 and 62. Each test seeds the approved lines first (the worker runs as the instance starts),
 * then waits for UserTask_ReviewActualManhours. Emails are read from Mailpit; a subject is the same for every test of a CBS and week, so a test counts the
 * mails that arrive after it started. The PCON Manager address is set for the whole class (D3).
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(AssignmentFileApiTest.TestClockConfig.class)
class VarianceApiTest {

    static final Instant TODAY = Instant.parse("2026-11-20T07:00:00Z"); // 10:00 in Riyadh
    static final String PROCESS = "Process_PPC_WeeklyProgressPeriod";
    static final String PREPARE = "ServiceTask_PrepareVarianceCheck";
    static final String SEND = "ServiceTask_SendVarianceAlerts";
    static final String REVIEW = "UserTask_ReviewActualManhours";
    static final String RECORD = "ServiceTask_RecordCbsReview";
    static final String APPROVE_WEEK = "UserTask_ApproveWeeklyProgress";
    static final LocalDate W19 = LocalDate.of(2026, 11, 19);
    static final LocalDate W26 = LocalDate.of(2026, 11, 26);
    static final String PIP01 = "CBS-139-PIP-01";
    static final String PCON = "pcon.manager@star.example";
    static final String MESSAGE = "Man-hours on CBS-139-PIP-01 are 20.00% over the Current Forecast (1200.00 of 1000.00 MH), week ending 19 Nov 2026";
    static final String SUBJECT = "Man-hour alert: CBS-139-PIP-01, week ending 19 Nov 2026"; // ASSUMPTION (plan Q12): Jira gives only the message

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

    private AssignmentFlow flow;
    private BookHoursFixture fixture;
    private BookHoursFixture.FmsIds sheets;
    private long instance;
    private final Map<String, Integer> mailsBefore = new java.util.HashMap<>();

    /** All test classes share one Camunda: no weekly instance left waiting by another class may take this class's tasks. */
    @BeforeAll
    static void noInstanceLeftWaiting() {
        Mailpit.start();
        IntegrationApp.background();
        cancelWaiting();
    }

    private static void cancelWaiting() {
        for (String element : new String[] {"ServiceTask_OpenProgressPeriod", "ServiceTask_GenerateFmsHoursFile", "UserTask_SendHoursToFms", "ServiceTask_RecordFmsHandOff",
                "UserTask_UploadCompletionReport", "ServiceTask_ReadCompletionReport", "UserTask_ApproveCompletion", "ServiceTask_RecordCompletionApproval",
                "ServiceTask_PrepareProgressCheck", "BusinessRuleTask_CheckProgressRule", "ServiceTask_RecordProgressResult", "UserTask_ApproveRetraction",
                "ServiceTask_RecordRetractionDecision", "ServiceTask_CalculateEarnedManhours", PREPARE, "BusinessRuleTask_CheckManhourVariance",
                "BusinessRuleTask_CheckCostVariance", SEND, REVIEW, RECORD, APPROVE_WEEK}) {
            CamundaSteps.cancelInstancesWaitingAt(element);
        }
    }

    @BeforeEach
    void background() {
        cancelWaiting();
        clock.set(TODAY);
        fixture = new BookHoursFixture(jdbc);
        BookHoursFixture.Ids ids = fixture.reset();
        sheets = fixture.fmsBackground(ids); // CBS-139-PIP-01 on E1021 and E1099 (A-2201); WP-000470's sheet (E1150) has no CBS
        new RevisionFlow(port, jdbc).seedAppUsers();
        flow = new AssignmentFlow(port, jdbc);
        for (String address : List.of("cost-controller@example.com", PCON)) {
            mailsBefore.put(address, (int) Mailpit.withSubject(SUBJECT).stream().filter(m -> m.to().equals(address)).count());
        }
    }

    // --- the scenarios ----------------------------------------------------------------------------------------------------

    @Test
    void BPA64_exactly15PercentNoAlert() {
        approvedHours(sheets.e1021(), "1150.00");
        runWeek(W19);

        awaitTask(instance, REVIEW);

        JsonNode row = cbsRow(W19, PIP01);
        assertThat(row.path("result").asText()).isEqualTo("WITHIN");
        assertThat(row.path("variancePct").decimalValue()).isEqualByComparingTo("15.00");
        assertThat(row.path("alert").asBoolean()).isFalse();
        assertThat(alertCount(PIP01)).as("exactly 15% is no alert (FRD-22 §22.10 \"> 15%\")").isZero();
    }

    @Test
    void BPA64_over15PercentAlerts() {
        approvedHours(sheets.e1021(), "1200.00");
        runWeek(W19);

        awaitTask(instance, REVIEW);

        assertThat(cbsRow(W19, PIP01).path("alert").asBoolean()).isTrue();
        assertThat(cbsRow(W19, PIP01).path("result").asText()).isEqualTo("OVER_15_PCT");
        List<Mailpit.Mail> mails = poll(() -> mailsTo("cost-controller@example.com"), m -> m.size() == 1, "one email to cost-controller");
        assertThat(mails.get(0).body()).contains(MESSAGE);
        assertThat(alertRows("cost-controller", "InSystem")).as("one in-system alert").isEqualTo(1);
        assertThat(alertRows("cost-controller", "Email")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT variance_pct FROM bpa.variance_alert WHERE recipient_username = 'cost-controller' AND channel = 'InSystem'", BigDecimal.class))
                .isEqualByComparingTo("20.00");
        assertThat(myAlerts("cost-controller")).extracting(a -> a.path("message").asText()).containsExactly(MESSAGE);
    }

    @Test
    void BPA64_underForecastNoAlert() {
        approvedHours(sheets.e1021(), "700.00");
        runWeek(W19);

        awaitTask(instance, REVIEW);

        assertThat(alertCount(PIP01)).isZero();
        assertThat(cbsRow(W19, PIP01).path("result").asText()).isEqualTo("WITHIN");
        assertThat(cbsRow(W19, PIP01).path("etc").decimalValue()).isEqualByComparingTo("300.00");
    }

    @Test
    void BPA64_noForecastNoAlert() {
        jdbc.update("UPDATE bpa.digital_timesheet SET cbs_code_ref = 'CBS-140-STR-01' WHERE id = ?", sheets.e1099()); // no cbs_forecast row (plan Q13)
        approvedHours(sheets.e1099(), "50.00");
        runWeek(W19);

        awaitTask(instance, REVIEW);

        JsonNode row = cbsRow(W19, "CBS-140-STR-01");
        assertThat(row.path("result").asText()).isEqualTo("NO_FORECAST");
        assertThat(row.path("alert").asBoolean()).isFalse();
        assertThat(row.path("actualManhours").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(alertCount("CBS-140-STR-01")).isZero();
    }

    @Test
    void BPA64_noCbsLinesNeverAlert() {
        approvedHours(sheets.e1150OnWp470(), "40.00"); // WP-000470's time sheet has no CBS (D40)
        runWeek(W19);

        awaitTask(instance, REVIEW);

        JsonNode week = cbsActuals(W19);
        assertThat(week.path("noCbsManhours").decimalValue()).isEqualByComparingTo("40.00");
        assertThat(cbsCodes(week)).as("\"No CBS\" is never a row of the check").doesNotContain("No CBS");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.variance_alert", Integer.class)).isZero();
    }

    @Test
    void BPA64_alertSentOncePerPeriod() {
        approvedHours(sheets.e1021(), "1200.00");
        runWeek(W19);
        awaitTask(instance, REVIEW);
        poll(() -> mailsTo("cost-controller@example.com"), m -> m.size() == 1, "the first email to cost-controller");

        // ServiceTask_SendVarianceAlerts runs again for the same week (a retry), as a second instance started at the step with the same result
        long again = startAt(SEND, Map.of("progressPeriodId", periodId(W19).toString(), "weekEnding", W19.toString(), "manhourCheckResults", List.of(overResult())));
        awaitTask(again, REVIEW);

        assertThat(mailsTo("cost-controller@example.com")).as("still one email").hasSize(1);
        assertThat(alertRows("cost-controller", "InSystem")).isEqualTo(1);
        assertThat(alertRows("cost-controller", "Email")).isEqualTo(1);
    }

    @Test
    void BPA64_costControllerReviewsWithRemarks() {
        approvedHours(sheets.e1021(), "1200.00");
        runWeek(W19);
        String task = awaitTask(instance, REVIEW);

        HttpResponse<String> done = review(task, "cost-controller", List.of(Map.of("cbsCodeRef", PIP01, "remark", "Rework on spools")));

        assertThat(done.statusCode()).as(done.body()).isEqualTo(204);
        Map<String, Object> row = poll(() -> reviewRow(PIP01), r -> r != null, "the review of " + PIP01);
        assertThat(row.get("remark")).isEqualTo("Rework on spools");
        assertThat(row.get("reviewed_by")).isEqualTo("cost-controller");
        assertThat(row.get("reviewed_at")).isNotNull();
        JsonNode next = poll(() -> openTasks(instance, APPROVE_WEEK).stream().findFirst(), t -> t.isPresent(), APPROVE_WEEK + " of " + instance).orElseThrow();
        List<String> groups = new ArrayList<>();
        next.path("candidateGroups").forEach(g -> groups.add(g.asText()));
        assertThat(groups).containsExactly("project-manager");
    }

    @Test
    void BPA64_otherPersonasCannotSeeCbsActuals() {
        approvedHours(sheets.e1021(), "1200.00");
        runWeek(W19);
        awaitTask(instance, REVIEW);

        HttpResponse<String> refused = flow.loggedInAs("supervisor").get("/api/v1/progress-periods/2026-11-19/cbs-actuals");

        assertThat(refused.statusCode()).isEqualTo(403);
    }

    @Test
    void BPA64_alertedCbsNeedsARemark() {
        approvedHours(sheets.e1021(), "1200.00");
        runWeek(W19);
        String task = awaitTask(instance, REVIEW);

        HttpResponse<String> refused = review(task, "cost-controller", List.of(Map.of("cbsCodeRef", PIP01, "remark", "")));

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(json(refused.body()).path("detail").asText()).isEqualTo("A remark is required for CBS-139-PIP-01 (alert raised)");
        assertThat(openTasks(instance, REVIEW)).as("the task stays open").hasSize(1);
    }

    @Test
    void BPA64_negativeEtcIsHighlighted() {
        approvedHours(sheets.e1021(), "1200.00");
        runWeek(W19);
        awaitTask(instance, REVIEW);

        JsonNode row = cbsRow(W19, PIP01);

        assertThat(row.path("etc").decimalValue()).isEqualByComparingTo("-200.00");
        assertThat(jdbc.queryForList("SELECT DISTINCT alert_type FROM bpa.variance_alert WHERE cbs_code_ref = ?", String.class, PIP01))
                .as("no alert other than the man-hour alert").containsExactly("Manhour");
    }

    @Test
    void BPA64_pconManagerAlsoReceivesManhourAlert() {
        approvedHours(sheets.e1021(), "1200.00");
        runWeek(W19);
        awaitTask(instance, REVIEW);

        List<Mailpit.Mail> mails = poll(() -> mailsTo(PCON), m -> m.size() == 1, "one email to the PCON Manager");

        assertThat(mails.get(0).body()).contains(MESSAGE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.variance_alert WHERE recipient_email = ? AND channel = 'Email'", Integer.class, PCON)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.variance_alert WHERE recipient_email = ? AND channel = 'InSystem'", Integer.class, PCON))
                .as("the PCON Manager has no login: email only").isZero();
    }

    // --- extras (not Gherkin scenarios) ---------------------------------------------------------------------------------------

    /** The Outline's second row: 1150.01 is above 15%. */
    @Test
    void aBoundaryJustAbove15PercentAlerts() {
        approvedHours(sheets.e1021(), "1150.01");
        runWeek(W19);

        awaitTask(instance, REVIEW);

        assertThat(cbsRow(W19, PIP01).path("alert").asBoolean()).isTrue();
        assertThat(alertRows("cost-controller", "InSystem")).isEqualTo(1);
    }

    @Test
    void aNewWeekAlertsAgain() {
        approvedHours(sheets.e1021(), "1200.00");
        runWeek(W19);
        awaitTask(instance, REVIEW);
        poll(() -> mailsTo("cost-controller@example.com"), m -> m.size() == 1, "the first week's email");

        long second = runWeek(W26);
        awaitTask(second, REVIEW);

        String next = "Man-hour alert: CBS-139-PIP-01, week ending 26 Nov 2026";
        assertThat(poll(() -> Mailpit.withSubject(next), m -> m.stream().anyMatch(x -> x.to().equals("cost-controller@example.com")), "an email for the next week")).isNotEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.variance_alert WHERE recipient_username = 'cost-controller' AND channel = 'InSystem'", Integer.class)).isEqualTo(2);
    }

    @Test
    void aRemarkOver500IsRefused() {
        approvedHours(sheets.e1021(), "1200.00");
        runWeek(W19);
        String task = awaitTask(instance, REVIEW);

        HttpResponse<String> refused = review(task, "cost-controller", List.of(Map.of("cbsCodeRef", PIP01, "remark", "x".repeat(501))));

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(json(refused.body()).path("detail").asText()).isEqualTo("Remark can be at most 500 characters");
    }

    @Test
    void aCbsThatIsNotOfTheWeekIsRefused() {
        approvedHours(sheets.e1021(), "700.00");
        runWeek(W19);
        String task = awaitTask(instance, REVIEW);

        HttpResponse<String> refused = review(task, "cost-controller", List.of(Map.of("cbsCodeRef", "CBS-999-XXX-01", "remark", "n/a")));

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(json(refused.body()).path("detail").asText()).isEqualTo("CBS is not part of this review");
    }

    @Test
    void otherPersonasCannotReviewActualManhours() {
        approvedHours(sheets.e1021(), "700.00");
        runWeek(W19);
        String task = awaitTask(instance, REVIEW);

        HttpResponse<String> refused = review(task, "ppc-team", List.of(Map.of("cbsCodeRef", PIP01, "remark", "x")));

        assertThat(refused.statusCode()).isEqualTo(403);
    }

    @Test
    void projectManagerCanReadCbsActuals() {
        approvedHours(sheets.e1021(), "700.00");
        runWeek(W19);
        awaitTask(instance, REVIEW);

        HttpResponse<String> read = flow.loggedInAs("project-manager").get("/api/v1/progress-periods/2026-11-19/cbs-actuals");

        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(flow.loggedInAs("cost-controller").get("/api/v1/progress-periods/2026-11-26/cbs-actuals").statusCode()).as("no period for that week").isEqualTo(404);
    }

    @Test
    void myVarianceAlertsShowsOnlyMine() {
        approvedHours(sheets.e1021(), "1200.00");
        runWeek(W19);
        awaitTask(instance, REVIEW);

        assertThat(myAlerts("cost-controller")).hasSize(1);
        assertThat(myAlerts("project-manager")).as("the alert is the Cost Controller's").isEmpty();
        // BPA-65: any logged-in persona may ask and gets only its own alerts, so a supervisor gets an empty list (was 403 in BPA-64)
        HttpResponse<String> supervisor = flow.loggedInAs("supervisor").get("/api/v1/me/variance-alerts");
        assertThat(supervisor.statusCode()).isEqualTo(200);
        assertThat(json(supervisor.body())).isEmpty();
    }

    @Test
    void aSentBackReviewUpdatesTheRemark() {
        approvedHours(sheets.e1021(), "1200.00");
        runWeek(W19);
        String task = awaitTask(instance, REVIEW);
        assertThat(review(task, "cost-controller", List.of(Map.of("cbsCodeRef", PIP01, "remark", "Rework on spools"))).statusCode()).isEqualTo(204);
        poll(() -> reviewRow(PIP01), r -> r != null, "the first review");

        // the Project Manager sends the week back (BPA-66): the record step runs again for the same week with a new remark
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("progressPeriodId", periodId(W19).toString());
        variables.put("weekEnding", W19.toString());
        variables.put("cbsReviews", List.of(Map.of("cbsCodeRef", PIP01, "remark", "Spools reworked and rechecked")));
        variables.put("completedBy", "cost-controller");
        startAt(RECORD, variables);

        Map<String, Object> row = poll(() -> reviewRow(PIP01), r -> r != null && "Spools reworked and rechecked".equals(r.get("remark")), "the updated remark");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.cbs_review", Integer.class)).as("one row for the period and CBS").isEqualTo(1);
        assertThat(row.get("reviewed_by")).isEqualTo("cost-controller");
    }

    // --- helpers ----------------------------------------------------------------------------------------------------------------

    /** Approved hours on the time sheet, as one line (the database has no daily limit; the Book hours worker does). */
    private void approvedHours(UUID timesheet, String hours) {
        fixture.line(timesheet, LocalDate.of(2026, 11, 10), "Cutting", new BigDecimal(hours), "Approved");
    }

    /** A week's period (its instance linked after the start) and its instance, started at the variance check. */
    private long runWeek(LocalDate weekEnding) {
        jdbc.update("""
                INSERT INTO bpa.progress_period (week_ending, cut_off_at, status, process_instance_key, created_by, updated_by)
                VALUES (?, ?, 'CutOff', ?, 'test', 'test')""", java.sql.Date.valueOf(weekEnding),
                java.sql.Timestamp.from(weekEnding.atTime(20, 59).toInstant(java.time.ZoneOffset.UTC)), 9000000100L + weekEnding.getDayOfMonth());
        UUID period = periodId(weekEnding);
        long started = startAt(PREPARE, Map.of("progressPeriodId", period.toString(), "weekEnding", weekEnding.toString()));
        jdbc.update("UPDATE bpa.progress_period SET process_instance_key = ? WHERE id = ?", started, period);
        if (instance == 0 || weekEnding.equals(W19)) {
            instance = started;
        }
        return started;
    }

    private UUID periodId(LocalDate weekEnding) {
        return jdbc.queryForObject("SELECT id FROM bpa.progress_period WHERE week_ending = ?", UUID.class, java.sql.Date.valueOf(weekEnding));
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

    private static Map<String, Object> overResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cbsCodeRef", PIP01);
        result.put("actualManhours", new BigDecimal("1200.00"));
        result.put("currentForecastManhours", new BigDecimal("1000.00"));
        result.put("manhourAlert", true);
        result.put("manhourResult", "OVER_15_PCT");
        return result;
    }

    private HttpResponse<String> review(String taskKey, String persona, List<Map<String, Object>> reviews) {
        return flow.loggedInAs(persona).post("/api/v1/tasks/" + taskKey + "/complete", toJson(Map.of("variables", Map.of("cbsReviews", reviews))));
    }

    private JsonNode cbsActuals(LocalDate weekEnding) {
        HttpResponse<String> read = flow.loggedInAs("cost-controller").get("/api/v1/progress-periods/" + weekEnding + "/cbs-actuals");
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        return json(read.body());
    }

    private JsonNode cbsRow(LocalDate weekEnding, String cbs) {
        for (JsonNode row : cbsActuals(weekEnding).path("cbs")) {
            if (cbs.equals(row.path("cbsCodeRef").asText())) {
                return row;
            }
        }
        throw new AssertionError(cbs + " is not a row of the week ending " + weekEnding);
    }

    private static List<String> cbsCodes(JsonNode week) {
        List<String> codes = new ArrayList<>();
        week.path("cbs").forEach(r -> codes.add(r.path("cbsCodeRef").asText()));
        return codes;
    }

    private List<JsonNode> myAlerts(String persona) {
        HttpResponse<String> read = flow.loggedInAs(persona).get("/api/v1/me/variance-alerts");
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        List<JsonNode> alerts = new ArrayList<>();
        json(read.body()).forEach(alerts::add);
        return alerts;
    }

    /** The emails to the address that arrived after this test started (the subject is the same for every test of a CBS and week; the order does not matter). */
    private List<Mailpit.Mail> mailsTo(String address) {
        List<Mailpit.Mail> all = Mailpit.withSubject(SUBJECT).stream().filter(m -> m.to().equals(address)).toList();
        return all.subList(0, Math.max(0, all.size() - mailsBefore.getOrDefault(address, 0)));
    }

    private int alertCount(String cbs) {
        return jdbc.queryForObject("SELECT count(*) FROM bpa.variance_alert WHERE cbs_code_ref = ?", Integer.class, cbs);
    }

    private int alertRows(String recipient, String channel) {
        return jdbc.queryForObject("SELECT count(*) FROM bpa.variance_alert WHERE recipient_username = ? AND channel = ?", Integer.class, recipient, channel);
    }

    private Map<String, Object> reviewRow(String cbs) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT remark, reviewed_by, reviewed_at FROM bpa.cbs_review WHERE cbs_code_ref = ?", cbs);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
