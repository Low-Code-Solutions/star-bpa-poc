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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BPA-65 (E2-15), the weekly part: the Project Manager is alerted when a CBS's actual cost is more than 10% over its Revised Budget. One test per Gherkin scenario, named as
 * in the Jira Definition of Done, plus the two scenarios the Definition of Done does not name (plan Q10). The daily close scenarios are in {@link DailyCloseCostCheckTest}.
 * Background: today is 20 Nov 2026 (Asia/Riyadh); CBS-139-PIP-01 has Revised Budget cost 250000.00 SAR (seed); the weekly instance for the week ending 19 Nov 2026 starts at
 * ServiceTask_PrepareVarianceCheck and the real workers, decisions and gateway run until UserTask_ReviewActualManhours. The actual cost is set per test in cbs_actual_cost
 * (the fixture restores the seed). Emails are read from Mailpit; a subject is the same for every test of a CBS and date, so a test counts the mails that arrive after it started.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(AssignmentFileApiTest.TestClockConfig.class)
class CostVarianceApiTest {

    static final Instant TODAY = Instant.parse("2026-11-20T07:00:00Z"); // 10:00 in Riyadh
    static final String PROCESS = "Process_PPC_WeeklyProgressPeriod";
    static final String PREPARE = "ServiceTask_PrepareVarianceCheck";
    static final String SEND = "ServiceTask_SendVarianceAlerts";
    static final String REVIEW = "UserTask_ReviewActualManhours";
    static final String RECORD = "ServiceTask_RecordCbsReview";
    static final String APPROVE_WEEK = "UserTask_ApproveWeeklyProgress";
    static final LocalDate W19 = LocalDate.of(2026, 11, 19);
    static final String PIP01 = "CBS-139-PIP-01";
    static final String PM = "project-manager@example.com";
    static final String CC = "cost-controller@example.com";
    static final String PCON = "pcon.manager@star.example";
    static final String SUBJECT = "Cost alert: CBS-139-PIP-01, 20 Nov 2026"; // ASSUMPTION (plan Q11): Jira gives only the message
    static final String MESSAGE_20 = "Cost on CBS-139-PIP-01 is 20.00% over the Revised Budget (300000.00 of 250000.00 SAR), 20 Nov 2026";

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
    private final Map<String, Integer> mailsBefore = new HashMap<>();

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
        sheets = fixture.fmsBackground(ids); // CBS-139-PIP-01 on E1021 and E1099 (A-2201)
        new RevisionFlow(port, jdbc).seedAppUsers();
        flow = new AssignmentFlow(port, jdbc);
        for (String address : List.of(PM, CC, PCON)) {
            mailsBefore.put(address, (int) Mailpit.withSubject(SUBJECT).stream().filter(m -> m.to().equals(address)).count());
        }
    }

    // --- the scenarios ----------------------------------------------------------------------------------------------------

    @Test
    void BPA65_exactly10PercentNoAlert() {
        actualCost(PIP01, LocalDate.of(2026, 11, 1), "275000.00");
        runWeek(W19);

        awaitTask(instance, REVIEW);

        JsonNode row = cbsRow(W19, PIP01);
        assertThat(row.path("costResult").asText()).isEqualTo("WITHIN");
        assertThat(row.path("costVariancePct").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(row.path("costAlert").asBoolean()).isFalse();
        assertThat(costAlertCount(PIP01)).as("exactly 10% is no alert (FRD-22 §22.10 \"> 10%\")").isZero();
    }

    @Test
    void BPA65_over10PercentAlerts() {
        actualCost(PIP01, LocalDate.of(2026, 11, 1), "275000.01");
        runWeek(W19);

        awaitTask(instance, REVIEW);

        JsonNode row = cbsRow(W19, PIP01);
        assertThat(row.path("costResult").asText()).isEqualTo("OVER_10_PCT");
        assertThat(row.path("costAlert").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.variance_alert WHERE alert_type = 'Cost' AND cbs_code_ref = ?", Integer.class, PIP01)).isPositive();
        assertThat(jdbc.queryForObject("SELECT DISTINCT variance_pct FROM bpa.variance_alert WHERE alert_type = 'Cost' AND cbs_code_ref = ?", BigDecimal.class, PIP01))
                .as("275000.01 is 10.000004% over, shown as 10.00").isEqualByComparingTo("10.00");
    }

    @Test
    void BPA65_noRevisedBudgetNoAlert() {
        actualCost("CBS-140-STR-01", LocalDate.of(2026, 11, 1), "10000.00"); // no cbs_forecast row, so no Revised Budget (plan Q5)
        runWeek(W19);

        awaitTask(instance, REVIEW);

        JsonNode row = cbsRow(W19, "CBS-140-STR-01");
        assertThat(row.path("costResult").asText()).isEqualTo("NO_BUDGET");
        assertThat(row.path("costAlert").asBoolean()).isFalse();
        assertThat(row.path("actualCost").decimalValue()).isEqualByComparingTo("10000.00");
        assertThat(costAlertCount("CBS-140-STR-01")).isZero();
    }

    @Test
    void BPA65_actualCostSumsMonthsToPeriod() {
        actualCost(PIP01, LocalDate.of(2026, 10, 1), "150000.00");
        actualCost(PIP01, LocalDate.of(2026, 11, 1), "130000.00");
        actualCost(PIP01, LocalDate.of(2026, 12, 1), "50000.00");
        runWeek(W19);

        awaitTask(instance, REVIEW);

        JsonNode row = cbsRow(W19, PIP01);
        assertThat(row.path("actualCost").decimalValue()).as("October and November, not December").isEqualByComparingTo("280000.00");
        assertThat(row.path("costAlert").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT DISTINCT actual_value FROM bpa.variance_alert WHERE alert_type = 'Cost' AND cbs_code_ref = ?", BigDecimal.class, PIP01))
                .isEqualByComparingTo("280000.00");
    }

    @Test
    void BPA65_costAlertGoesToProjectManager() {
        actualCost(PIP01, LocalDate.of(2026, 11, 1), "300000.00");
        runWeek(W19);
        awaitTask(instance, REVIEW);

        List<Mailpit.Mail> mails = poll(() -> mailsTo(PM), m -> m.size() == 1, "one email to project-manager");
        assertThat(mails.get(0).body()).contains(MESSAGE_20);
        assertThat(costRows("project-manager", "InSystem")).as("one in-system alert").isEqualTo(1);
        assertThat(costRows("project-manager", "Email")).isEqualTo(1);
        assertThat(myAlerts("project-manager")).extracting(a -> a.path("message").asText()).containsExactly(MESSAGE_20);
        assertThat(costRows("cost-controller", "InSystem") + costRows("cost-controller", "Email")).as("cost-controller gets no cost alert").isZero();
        assertThat(mailsTo(CC)).as("no cost email to cost-controller").isEmpty();
    }

    /** No name in the Definition of Done (plan Q10): the scenario "My alerts list shows my alerts only". */
    @Test
    void BPA65_myAlertsListShowsMyAlertsOnly() {
        actualCost(PIP01, LocalDate.of(2026, 11, 1), "300000.00");
        fixture.line(sheets.e1021(), LocalDate.of(2026, 11, 10), "Cutting", new BigDecimal("1200.00"), "Approved"); // 1200.00 of 1000.00 MH: a man-hour alert for the Cost Controller
        runWeek(W19);
        awaitTask(instance, REVIEW);

        List<JsonNode> mine = myAlerts("project-manager");

        assertThat(mine).extracting(a -> a.path("alertType").asText()).as("a project-manager sees cost alerts only, no man-hour alert").containsExactly("Cost");
        assertThat(mine.get(0).path("message").asText()).isEqualTo(MESSAGE_20);
        assertThat(myAlerts("cost-controller")).extracting(a -> a.path("alertType").asText()).as("the Cost Controller sees the man-hour alert only").containsExactly("Manhour");
    }

    @Test
    void BPA65_otherPersonasCannotSeeCostAlerts() {
        actualCost(PIP01, LocalDate.of(2026, 11, 1), "300000.00");
        runWeek(W19);
        awaitTask(instance, REVIEW);

        HttpResponse<String> read = flow.loggedInAs("supervisor").get("/api/v1/me/variance-alerts");

        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        assertThat(json(read.body())).isEmpty();
    }

    @Test
    void BPA65_costVarianceOnCostControllerReview() {
        actualCost(PIP01, LocalDate.of(2026, 11, 1), "300000.00");
        runWeek(W19);
        String task = awaitTask(instance, REVIEW);

        JsonNode row = cbsRow(W19, PIP01);
        assertThat(row.path("actualCost").decimalValue()).isEqualByComparingTo("300000.00");
        assertThat(row.path("revisedBudgetCost").decimalValue()).isEqualByComparingTo("250000.00");
        assertThat(row.path("costVariancePct").decimalValue()).isEqualByComparingTo("20.00");
        assertThat(row.path("costAlert").asBoolean()).isTrue();

        HttpResponse<String> refused = review(task, "cost-controller", List.of(Map.of("cbsCodeRef", PIP01, "remark", "")));
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(json(refused.body()).path("detail").asText()).isEqualTo("A remark is required for CBS-139-PIP-01 (alert raised)");
        assertThat(openTasks(instance, REVIEW)).as("the task stays open").hasSize(1);
    }

    /** Jira's name says period; the scenario says once per day (the weekly check sends the cost alert at most once a day). */
    @Test
    void BPA65_costAlertSentOncePerPeriod() {
        actualCost(PIP01, LocalDate.of(2026, 11, 1), "300000.00");
        runWeek(W19);
        awaitTask(instance, REVIEW);
        poll(() -> mailsTo(PM), m -> m.size() == 1, "the first email to project-manager");

        // ServiceTask_SendVarianceAlerts runs again the same day (a retry): a second instance started at the step with the same results
        long again = startAt(SEND, Map.of("progressPeriodId", periodId(W19).toString(), "weekEnding", W19.toString(), "costCheckResults", List.of(overResult())));
        awaitTask(again, REVIEW);

        assertThat(mailsTo(PM)).as("still one email").hasSize(1);
        assertThat(costRows("project-manager", "InSystem")).isEqualTo(1);
        assertThat(costRows("project-manager", "Email")).isEqualTo(1);
    }

    @Test
    void BPA65_pconManagerAlsoReceivesCostAlert() {
        actualCost(PIP01, LocalDate.of(2026, 11, 1), "280000.00");
        runWeek(W19);
        awaitTask(instance, REVIEW);

        List<Mailpit.Mail> mails = poll(() -> mailsTo(PCON), m -> m.size() == 1, "one email to the PCON Manager");

        String text = "Cost on CBS-139-PIP-01 is 12.00% over the Revised Budget (280000.00 of 250000.00 SAR), 20 Nov 2026";
        assertThat(mails.get(0).body()).contains(text);
        assertThat(poll(() -> mailsTo(PM), m -> m.size() == 1, "the same email to project-manager").get(0).body()).contains(text);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.variance_alert WHERE recipient_email = ? AND alert_type = 'Cost' AND channel = 'Email'", Integer.class, PCON))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.variance_alert WHERE recipient_email = ? AND channel = 'InSystem'", Integer.class, PCON))
                .as("the PCON Manager has no login: email only").isZero();
    }

    // --- helpers ----------------------------------------------------------------------------------------------------------------

    /** The actual cost of a CBS for a month, as the seeded D365 copy. */
    private void actualCost(String cbs, LocalDate month, String amount) {
        jdbc.update("""
                INSERT INTO bpa.cbs_actual_cost (cbs_code_ref, period_month, actual_cost, created_by, updated_by) VALUES (?, ?, ?, 'test', 'test')
                ON CONFLICT (cbs_code_ref, period_month) DO UPDATE SET actual_cost = EXCLUDED.actual_cost""", cbs, java.sql.Date.valueOf(month), new BigDecimal(amount));
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
        instance = started;
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
        result.put("revisedBudgetCost", new BigDecimal("250000.00"));
        result.put("actualCost", new BigDecimal("300000.00"));
        result.put("costAlert", true);
        result.put("costResult", "OVER_10_PCT");
        return result;
    }

    private HttpResponse<String> review(String taskKey, String persona, List<Map<String, Object>> reviews) {
        return flow.loggedInAs(persona).post("/api/v1/tasks/" + taskKey + "/complete", toJson(Map.of("variables", Map.of("cbsReviews", reviews))));
    }

    private JsonNode cbsRow(LocalDate weekEnding, String cbs) {
        HttpResponse<String> read = flow.loggedInAs("cost-controller").get("/api/v1/progress-periods/" + weekEnding + "/cbs-actuals");
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        for (JsonNode row : json(read.body()).path("cbs")) {
            if (cbs.equals(row.path("cbsCodeRef").asText())) {
                return row;
            }
        }
        throw new AssertionError(cbs + " is not a row of the week ending " + weekEnding);
    }

    private List<JsonNode> myAlerts(String persona) {
        HttpResponse<String> read = flow.loggedInAs(persona).get("/api/v1/me/variance-alerts");
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        List<JsonNode> alerts = new ArrayList<>();
        json(read.body()).forEach(alerts::add);
        return alerts;
    }

    /** The cost emails to the address that arrived after this test started (the subject is the same for every test; the order does not matter). */
    private List<Mailpit.Mail> mailsTo(String address) {
        List<Mailpit.Mail> all = Mailpit.withSubject(SUBJECT).stream().filter(m -> m.to().equals(address)).toList();
        return all.subList(0, Math.max(0, all.size() - mailsBefore.getOrDefault(address, 0)));
    }

    private int costAlertCount(String cbs) {
        return jdbc.queryForObject("SELECT count(*) FROM bpa.variance_alert WHERE alert_type = 'Cost' AND cbs_code_ref = ?", Integer.class, cbs);
    }

    private int costRows(String recipient, String channel) {
        return jdbc.queryForObject("SELECT count(*) FROM bpa.variance_alert WHERE alert_type = 'Cost' AND recipient_username = ? AND channel = ?", Integer.class, recipient, channel);
    }
}
