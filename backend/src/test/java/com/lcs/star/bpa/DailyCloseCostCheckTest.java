package com.lcs.star.bpa;

import static com.lcs.star.bpa.CamundaSteps.json;
import static com.lcs.star.bpa.CamundaSteps.openTasks;
import static com.lcs.star.bpa.CamundaSteps.poll;
import static com.lcs.star.bpa.CamundaSteps.toJson;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.lcs.star.bpa.CamundaRest.Reply;
import com.lcs.star.bpa.progress.DailyCloseCostCheckJob;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
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
 * BPA-65 (E2-15), the daily close part (FRD-22 §22.10; the window is an ASSUMPTION of the story: three days before the month lock day up to and including it, 06:00
 * Asia/Riyadh). Plan Q1 option A: a scheduled job, so these tests set the injected clock to a date at 06:00 Riyadh and call {@link DailyCloseCostCheckJob#run()} (no real
 * waiting; the cron expression itself is not exercised). The lock day is the default 1, so the close is 28 Nov to 1 Dec 2026. CBS-139-PIP-01 has Revised Budget cost
 * 250000.00 SAR (seed). Emails are read from Mailpit; their subject carries the date, and a test counts the mails that arrive after it started.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(AssignmentFileApiTest.TestClockConfig.class)
class DailyCloseCostCheckTest {

    static final Instant WEEKLY_DAY = Instant.parse("2026-11-20T07:00:00Z"); // 10:00 in Riyadh
    static final String PROCESS = "Process_PPC_WeeklyProgressPeriod";
    static final String PREPARE = "ServiceTask_PrepareVarianceCheck";
    static final String SEND = "ServiceTask_SendVarianceAlerts";
    static final String REVIEW = "UserTask_ReviewActualManhours";
    static final String RECORD = "ServiceTask_RecordCbsReview";
    static final String APPROVE_WEEK = "UserTask_ApproveWeeklyProgress";
    static final LocalDate W19 = LocalDate.of(2026, 11, 19);
    static final LocalDate W26 = LocalDate.of(2026, 11, 26);
    static final String PIP01 = "CBS-139-PIP-01";
    static final String PM = "project-manager@example.com";
    static final String PCON = "pcon.manager@star.example";
    static final List<String> DATES = List.of("20 Nov 2026", "27 Nov 2026", "28 Nov 2026", "1 Dec 2026", "2 Dec 2026");

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
    DailyCloseCostCheckJob job;

    private AssignmentFlow flow;
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
        clock.set(WEEKLY_DAY);
        BookHoursFixture fixture = new BookHoursFixture(jdbc);
        BookHoursFixture.Ids ids = fixture.reset();
        fixture.fmsBackground(ids);
        new RevisionFlow(port, jdbc).seedAppUsers();
        flow = new AssignmentFlow(port, jdbc);
        for (String date : DATES) {
            mailsBefore.put(date, (int) Mailpit.withSubject(subject(date)).stream().filter(m -> m.to().equals(PM)).count());
        }
    }

    // --- the scenarios ----------------------------------------------------------------------------------------------------

    /** The Outline's rows 28 Nov and 1 Dec: the check runs and project-manager is alerted, with that day's date in the text. */
    @Test
    void BPA65_dailyCheckRunsDuringClose() {
        actualCost(PIP01, LocalDate.of(2026, 11, 1), "300000.00");

        runDaily("2026-11-28");
        assertThat(costRows("project-manager", "InSystem")).as("28 Nov: one in-system alert").isEqualTo(1);
        assertThat(costRows("project-manager", "Email")).isEqualTo(1);
        assertThat(poll(() -> mailsTo("28 Nov 2026"), m -> m.size() == 1, "the 28 Nov email to project-manager").get(0).body())
                .contains("Cost on CBS-139-PIP-01 is 20.00% over the Revised Budget (300000.00 of 250000.00 SAR), 28 Nov 2026");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.variance_alert WHERE alert_type = 'Cost' AND progress_period_id IS NULL", Integer.class))
                .as("a daily alert belongs to no week (plan Q3)").isPositive();

        runDaily("2026-12-01");
        assertThat(costRows("project-manager", "InSystem")).as("1 Dec: the lock day itself runs, a second alert").isEqualTo(2);
        assertThat(poll(() -> mailsTo("1 Dec 2026"), m -> m.size() == 1, "the 1 Dec email to project-manager").get(0).body())
                .contains("(300000.00 of 250000.00 SAR), 1 Dec 2026");
        assertThat(myAlerts("project-manager")).extracting(a -> a.path("alertDate").asText()).containsExactly("2026-12-01", "2026-11-28");
    }

    /** The Outline's rows 27 Nov and 2 Dec: outside the close the check does not run. A control run on 28 Nov shows the job does work inside the window. */
    @Test
    void BPA65_noDailyCheckOutsideClose() {
        actualCost(PIP01, LocalDate.of(2026, 11, 1), "300000.00");

        runDaily("2026-11-27");
        assertThat(costRows("project-manager", "InSystem")).as("27 Nov is outside the close").isZero();
        assertThat(mailsTo("27 Nov 2026")).isEmpty();

        runDaily("2026-11-28"); // control: inside the window the same job alerts
        assertThat(costRows("project-manager", "InSystem")).as("28 Nov is the first day of the close").isEqualTo(1);

        runDaily("2026-12-02");
        assertThat(costRows("project-manager", "InSystem")).as("2 Dec: the close has ended").isEqualTo(1);
        assertThat(mailsTo("2 Dec 2026")).isEmpty();
    }

    /** No name in the Definition of Done (plan Q10): "A daily and a weekly check on the same day alert once". */
    @Test
    void BPA65_aDailyAndAWeeklyCheckOnTheSameDayAlertOnce() {
        actualCost(PIP01, LocalDate.of(2026, 11, 1), "300000.00");
        clock.set(Instant.parse("2026-11-28T07:00:00Z"));
        runWeek(W26);
        awaitTask(instance, REVIEW);
        assertThat(costRows("project-manager", "InSystem")).as("the weekly check alerted").isEqualTo(1);

        runDaily("2026-11-28");

        assertThat(costRows("project-manager", "InSystem")).isEqualTo(1);
        assertThat(costRows("project-manager", "Email")).isEqualTo(1);
        assertThat(mailsTo("28 Nov 2026")).as("one email to project-manager that day").hasSize(1);
        assertThat(jdbc.queryForObject("SELECT DISTINCT alert_date FROM bpa.variance_alert WHERE alert_type = 'Cost' AND recipient_username = 'project-manager' AND channel = 'InSystem'",
                java.sql.Date.class)).isEqualTo(java.sql.Date.valueOf("2026-11-28"));
    }

    // --- extras (not Gherkin scenarios) ---------------------------------------------------------------------------------------

    @Test
    void aDailyAlertBeforeTheWeeklyCheckIsLinkedToTheWeek() {
        actualCost(PIP01, LocalDate.of(2026, 11, 1), "300000.00");
        runDaily("2026-11-28");
        assertThat(costRows("project-manager", "InSystem")).isEqualTo(1);

        runWeek(W26);
        String task = awaitTask(instance, REVIEW);

        assertThat(costRows("project-manager", "InSystem")).as("nothing is sent again").isEqualTo(1);
        assertThat(mailsTo("28 Nov 2026")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT progress_period_id FROM bpa.variance_alert WHERE alert_type = 'Cost' AND recipient_username = 'project-manager' AND channel = 'InSystem'",
                UUID.class)).as("the weekly check linked the row to its week").isEqualTo(periodId(W26));
        HttpResponse<String> refused = flow.loggedInAs("cost-controller").post("/api/v1/tasks/" + task + "/complete",
                toJson(Map.of("variables", Map.of("cbsReviews", List.of(Map.of("cbsCodeRef", PIP01, "remark", ""))))));
        assertThat(refused.statusCode()).as("the linked cost alert needs a remark").isEqualTo(400);
        assertThat(json(refused.body()).path("detail").asText()).isEqualTo("A remark is required for CBS-139-PIP-01 (alert raised)");
    }

    @Test
    void aNewDayAlertsAgain() {
        actualCost(PIP01, LocalDate.of(2026, 11, 1), "300000.00");
        runWeek(W19);
        awaitTask(instance, REVIEW);
        assertThat(costRows("project-manager", "InSystem")).isEqualTo(1);

        runDaily("2026-11-28");

        assertThat(costRows("project-manager", "InSystem")).as("a new day is a new alert").isEqualTo(2);
        assertThat(mailsTo("20 Nov 2026")).hasSize(1);
        assertThat(mailsTo("28 Nov 2026")).hasSize(1);
    }

    // --- helpers ----------------------------------------------------------------------------------------------------------------

    private static String subject(String date) {
        return "Cost alert: CBS-139-PIP-01, " + date;
    }

    /** The clock at 06:00 Asia/Riyadh (03:00 UTC) on the date, then the job. */
    private void runDaily(String isoDate) {
        clock.set(Instant.parse(isoDate + "T03:00:00Z"));
        job.run();
    }

    private void actualCost(String cbs, LocalDate month, String amount) {
        jdbc.update("""
                INSERT INTO bpa.cbs_actual_cost (cbs_code_ref, period_month, actual_cost, created_by, updated_by) VALUES (?, ?, ?, 'test', 'test')
                ON CONFLICT (cbs_code_ref, period_month) DO UPDATE SET actual_cost = EXCLUDED.actual_cost""", cbs, java.sql.Date.valueOf(month), new BigDecimal(amount));
    }

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

    private List<JsonNode> myAlerts(String persona) {
        HttpResponse<String> read = flow.loggedInAs(persona).get("/api/v1/me/variance-alerts");
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        List<JsonNode> alerts = new ArrayList<>();
        json(read.body()).forEach(alerts::add);
        return alerts;
    }

    /** The emails to project-manager with that date in the subject that arrived after this test started. */
    private List<Mailpit.Mail> mailsTo(String date) {
        List<Mailpit.Mail> all = Mailpit.withSubject(subject(date)).stream().filter(m -> m.to().equals(PM)).toList();
        return all.subList(0, Math.max(0, all.size() - mailsBefore.getOrDefault(date, 0)));
    }

    private int costRows(String recipient, String channel) {
        return jdbc.queryForObject("SELECT count(*) FROM bpa.variance_alert WHERE alert_type = 'Cost' AND recipient_username = ? AND channel = ?", Integer.class, recipient, channel);
    }
}
