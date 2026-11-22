package com.lcs.star.bpa;

import static com.lcs.star.bpa.CamundaSteps.json;
import static com.lcs.star.bpa.CamundaSteps.openTasks;
import static com.lcs.star.bpa.CamundaSteps.poll;
import static com.lcs.star.bpa.CamundaSteps.toJson;
import static com.lcs.star.bpa.TimesheetFileBuilder.timesheet;
import static org.assertj.core.api.Assertions.assertThat;

import com.lcs.star.bpa.CamundaRest.Reply;
import com.lcs.star.bpa.progress.MonthLockJob;
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
 * BPA-67 (E2-16), the hours: a Completed Work Pack takes no hours, and the monthly hours lock refuses lines dated in a locked month (S5; FRD-5-3 §5.3.3.3, §5.3.8.1). One
 * test per Gherkin scenario, named as in the Jira Definition of Done (the week scenarios are in {@link WeekLockApiTest}). Background: {@link BookHoursFixture} (WP-000457; with
 * the completion background, WP-000461 and its E1021 time sheet); the clock is set per test (today is 20 Nov 2026 Asia/Riyadh unless a scenario says otherwise). The lock day
 * (bpa.hours.month-lock-day) is set through the environment, which the app reads at each call; the month is locked by calling {@link MonthLockJob} directly with the clock at
 * its date (BPA-64's job, which BPA-67 does not change), so there is no real waiting.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(AssignmentFileApiTest.TestClockConfig.class)
class HoursLockApiTest {

    static final Instant TODAY = Instant.parse("2026-11-20T07:00:00Z"); // 10:00 in Riyadh
    static final String PROCESS = "Process_PPC_TimesheetToProgress";
    static final String BOOK_HOURS = "UserTask_BookHours";
    static final String VALIDATE = "UserTask_ValidateHours";
    static final LocalDate W19 = LocalDate.of(2026, 11, 19);
    static final String NOVEMBER_LOCKED = "Hours for November 2026 are locked";
    static final String COMPLETED = "Work Pack WP-000461 is Completed";

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
    MonthLockJob monthLock;

    @Autowired
    ConfigurableEnvironment environment;

    private BookHoursFixture fixture;
    private BookHoursFixture.Ids ids;
    private AssignmentFlow flow;

    /** All test classes share one Camunda: no task left waiting by another class may mix with this class's instances. */
    @BeforeAll
    static void noTaskLeftWaiting() {
        IntegrationApp.background();
        for (String element : new String[] {BOOK_HOURS, VALIDATE, "UserTask_ApproveHoursProduction", "UserTask_ApproveHoursPpc"}) {
            CamundaSteps.cancelInstancesWaitingAt(element);
        }
    }

    @BeforeEach
    void background() {
        environment.getPropertySources().remove("bpa67");
        clock.set(TODAY);
        fixture = new BookHoursFixture(jdbc);
        ids = fixture.reset();
        fixture.completionBackground(); // WP-000457 and WP-000461 In Progress, each with E1021's Draft time sheet
        flow = new AssignmentFlow(port, jdbc);
    }

    // --- the scenarios ----------------------------------------------------------------------------------------------------

    @Test
    void BPA67_completedWorkPackTakesNoHours() {
        jdbc.update("UPDATE bpa.workpack SET status = 'Completed' WHERE workpack_number = 'WP-000461'");

        HttpResponse<String> refused = add("supervisor", sheet461(), "2026-11-20", "Cutting", "2");

        assertRefused(refused, 409, COMPLETED);
        assertThat(lineCount(sheet461())).isZero();
    }

    /** The Returned line is corrected after the cut-off and sent again; a new line for the cut-off week stays refused (the story's text is the one before the send). */
    @Test
    void BPA67_returnedLineInCutOffWeekCanBeCorrected() {
        jdbc.update("""
                INSERT INTO bpa.progress_period (week_ending, cut_off_at, status, process_instance_key, created_by, updated_by)
                VALUES (?, ?, 'CutOff', 9000000019, 'test', 'test')""", java.sql.Date.valueOf(W19), java.sql.Timestamp.from(Instant.parse("2026-11-19T20:59:00Z")));
        UUID line = fixture.line(ids.timesheet("E1021"), W19, "Cutting", new BigDecimal("8"), "Returned");

        HttpResponse<String> corrected = flow.loggedInAs("supervisor").put("/api/v1/digital-timesheet-lines/" + line, toJson(Map.of("hours", 7)));

        assertThat(corrected.statusCode()).as(corrected.body()).isEqualTo(200);
        assertRefused(add("supervisor", ids.timesheet("E1021"), "2026-11-19", "Welding", "2"), 400, "Week ending 19 Nov 2026 is closed");
        send(startAtBookHours("WP-000457"));
        poll(() -> lineStatus(line), "Submitted"::equals, "the corrected line Submitted");
        assertThat(lineHours(line)).isEqualByComparingTo("7");
        assertThat(add("supervisor", ids.timesheet("E1021"), "2026-11-19", "Welding", "2").statusCode()).as("still refused").isBetween(400, 409);
    }

    /** The Outline: lock day 1 locks November on 1 Dec, lock day 16 on 16 Dec (and not on the 15th). */
    @Test
    void BPA67_hoursLockOnConfiguredMonthDay() {
        lockDay("1");
        clock.set(Instant.parse("2026-11-30T21:10:00Z")); // 1 Dec 00:10 Riyadh
        monthLock.run();
        assertThat(novemberLocks()).as("day 1: locked on 1 Dec").isEqualTo(1);
        assertRefused(add("supervisor", ids.timesheet("E1021"), "2026-11-30", "Cutting", "4"), 400, NOVEMBER_LOCKED);

        jdbc.update("DELETE FROM bpa.cbs_manhour_snapshot");
        jdbc.update("DELETE FROM bpa.hours_month_lock");
        lockDay("16");
        clock.set(Instant.parse("2026-12-14T21:10:00Z")); // 15 Dec 00:10 Riyadh
        monthLock.run();
        assertThat(novemberLocks()).as("day 16: not locked on 15 Dec").isZero();
        clock.set(Instant.parse("2026-12-15T21:10:00Z")); // 16 Dec 00:10 Riyadh
        monthLock.run();
        assertThat(novemberLocks()).as("day 16: locked on 16 Dec").isEqualTo(1);
        assertRefused(add("supervisor", ids.timesheet("E1021"), "2026-11-30", "Cutting", "4"), 400, NOVEMBER_LOCKED);
    }

    @Test
    void BPA67_lineRefusedAfterMonthLock() {
        lockDay("1");
        lockNovember("2026-12-02T07:00:00Z");

        HttpResponse<String> refused = add("supervisor", ids.timesheet("E1021"), "2026-11-30", "Cutting", "4");

        assertRefused(refused, 400, NOVEMBER_LOCKED);
        assertThat(lineCount(ids.timesheet("E1021"))).isZero();
    }

    @Test
    void BPA67_returnedLineCorrectableBeforeMonthLock() {
        lockDay("16");
        clock.set(Instant.parse("2026-12-10T07:00:00Z"));
        UUID line = fixture.line(ids.timesheet("E1021"), LocalDate.of(2026, 11, 30), "Cutting", new BigDecimal("8"), "Returned");

        HttpResponse<String> corrected = flow.loggedInAs("supervisor").put("/api/v1/digital-timesheet-lines/" + line, toJson(Map.of("hours", 4)));

        assertThat(corrected.statusCode()).as(corrected.body()).isEqualTo(200);
        send(startAtBookHours("WP-000457"));
        poll(() -> lineStatus(line), "Submitted"::equals, "the corrected line Submitted");
        assertThat(lineHours(line)).isEqualByComparingTo("4");
    }

    // --- extras (not Gherkin scenarios) ---------------------------------------------------------------------------------------

    @Test
    void aChangeOrDeleteInALockedMonthIsRefused() {
        UUID line = fixture.line(ids.timesheet("E1021"), LocalDate.of(2026, 11, 30), "Cutting", new BigDecimal("8"), "Returned");
        lockDay("1");
        lockNovember("2026-12-02T07:00:00Z");
        var supervisor = flow.loggedInAs("supervisor");

        assertRefused(supervisor.put("/api/v1/digital-timesheet-lines/" + line, toJson(Map.of("hours", 4))), 400, NOVEMBER_LOCKED);
        assertRefused(supervisor.delete("/api/v1/digital-timesheet-lines/" + line), 400, NOVEMBER_LOCKED);
        assertThat(lineStatus(line)).isEqualTo("Returned");
        assertThat(lineHours(line)).isEqualByComparingTo("8");
    }

    @Test
    void aCompletedWorkPackRefusesChangeAndDelete() {
        UUID line = fixture.line(sheet461(), LocalDate.of(2026, 11, 20), "Cutting", new BigDecimal("8"), "Draft");
        jdbc.update("UPDATE bpa.workpack SET status = 'Completed' WHERE workpack_number = 'WP-000461'");
        var supervisor = flow.loggedInAs("supervisor");

        assertRefused(supervisor.put("/api/v1/digital-timesheet-lines/" + line, toJson(Map.of("hours", 4))), 409, COMPLETED);
        assertRefused(supervisor.delete("/api/v1/digital-timesheet-lines/" + line), 409, COMPLETED);
        assertThat(lineHours(line)).isEqualByComparingTo("8");
    }

    /** An upload row is a form entry (E2-D4): the same two refusals are its outcome text. */
    @Test
    void anUploadRowOnACompletedWorkPackOrInALockedMonthGetsTheOutcome() {
        jdbc.update("UPDATE bpa.workpack SET status = 'Completed' WHERE workpack_number = 'WP-000461'");
        lockDay("1");
        lockNovember("2026-12-02T07:00:00Z");

        HttpResponse<String> response = flow.loggedInAs("supervisor").postFile("/api/v1/timesheet-files", "locked.xlsx",
                timesheet().row("WP-000461", "E1021", "20/11/2026", "Cutting", 2).row("WP-000457", "E1021", "30/11/2026", "Cutting", 4).xlsx());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        List<String> outcomes = new ArrayList<>();
        json(response.body()).path("rows").forEach(r -> outcomes.add(r.path("outcome").asText()));
        assertThat(outcomes).containsExactly(COMPLETED, NOVEMBER_LOCKED);
    }

    /** A Draft line of a locked month cannot be sent: the send step returns to Book hours with the message (ASSUMPTION, plan section 4). */
    @Test
    void aSendWithALineOfALockedMonthReturnsToBookHours() {
        UUID line = fixture.line(ids.timesheet("E1021"), LocalDate.of(2026, 11, 30), "Cutting", new BigDecimal("8"), "Draft");
        long instance = startAtBookHours("WP-000457");
        String first = awaitTask(instance, BOOK_HOURS);
        lockDay("1");
        lockNovember("2026-12-02T07:00:00Z");

        HttpResponse<String> sent = flow.loggedInAs("supervisor").post("/api/v1/tasks/" + first + "/complete", toJson(Map.of("variables", Map.of())));

        assertThat(sent.statusCode()).as(sent.body()).isEqualTo(204);
        String again = poll(() -> openTasks(instance, BOOK_HOURS).stream().map(t -> t.path("userTaskKey").asText()).filter(k -> !k.equals(first)).findFirst(),
                found -> found.isPresent(), "a new " + BOOK_HOURS + " of " + instance).orElseThrow();
        List<String> messages = new ArrayList<>();
        json(flow.loggedInAs("supervisor").get("/api/v1/tasks/" + again).body()).path("validationMessages").forEach(m -> messages.add(m.asText()));
        assertThat(messages).contains(NOVEMBER_LOCKED);
        assertThat(lineStatus(line)).isEqualTo("Draft");
    }

    /**
     * Review decision (Ahsan, 22 Nov 2026): a line of a locked month can no longer change, so the send leaves it alone (Draft or Returned, never sent) and sends the rest; one such
     * line never blocks the Work Pack's valid lines of other Workers and months.
     */
    @Test
    void aSendSkipsTheLinesOfALockedMonthAndSendsTheRest() {
        UUID november = fixture.line(ids.timesheet("E1021"), LocalDate.of(2026, 11, 30), "Cutting", new BigDecimal("8"), "Draft");
        UUID december = fixture.line(ids.timesheet("E1099"), LocalDate.of(2026, 12, 1), "Cutting", new BigDecimal("8"), "Draft");
        long instance = startAtBookHours("WP-000457");
        String book = awaitTask(instance, BOOK_HOURS);
        lockDay("1");
        lockNovember("2026-12-02T07:00:00Z");

        HttpResponse<String> sent = flow.loggedInAs("supervisor").post("/api/v1/tasks/" + book + "/complete", toJson(Map.of("variables", Map.of())));

        assertThat(sent.statusCode()).as(sent.body()).isEqualTo(204);
        awaitTask(instance, VALIDATE);
        assertThat(lineStatus(december)).as("the December line was sent").isEqualTo("Submitted");
        assertThat(lineStatus(november)).as("the November line was left as it was").isEqualTo("Draft");
    }

    /** Validated and Approved are refused for hours of a locked month (409); Returned stays allowed, so the hours do not get stuck (plan Q2). */
    @Test
    void aDecisionOnHoursOfALockedMonthIsRefusedUnlessItReturnsThem() {
        UUID line = fixture.line(ids.timesheet("E1021"), LocalDate.of(2026, 11, 30), "Cutting", new BigDecimal("8"), "Draft");
        long instance = startAtBookHours("WP-000457");
        send(instance);
        String validate = awaitTask(instance, VALIDATE);
        lockDay("1");
        lockNovember("2026-12-02T07:00:00Z");

        HttpResponse<String> refused = decide("timekeeper", validate, "Validated", null);
        HttpResponse<String> returned = decide("timekeeper", validate, "Returned", "Please check the date");

        assertRefused(refused, 409, NOVEMBER_LOCKED);
        assertThat(returned.statusCode()).as(returned.body()).isEqualTo(204);
        poll(() -> lineStatus(line), "Returned"::equals, "the line Returned");
    }

    // --- helpers ----------------------------------------------------------------------------------------------------------------

    private void lockDay(String day) {
        environment.getPropertySources().remove("bpa67");
        environment.getPropertySources().addFirst(new MapPropertySource("bpa67", Map.of("bpa.hours.month-lock-day", day)));
    }

    /** Today is {@code now}: BPA-64's job locks the month before it (lock day 1: 1 Dec or later locks November). */
    private void lockNovember(String now) {
        clock.set(Instant.parse(now));
        monthLock.run();
        assertThat(novemberLocks()).as("the setup locked November").isEqualTo(1);
    }

    private int novemberLocks() {
        return jdbc.queryForObject("SELECT count(*) FROM bpa.hours_month_lock WHERE month = DATE '2026-11-01'", Integer.class);
    }

    private UUID sheet461() {
        return jdbc.queryForObject("""
                SELECT t.id FROM bpa.digital_timesheet t JOIN bpa.worker w ON w.id = t.worker_id JOIN bpa.workpack p ON p.id = t.workpack_id
                WHERE p.workpack_number = 'WP-000461' AND w.employee_ref = 'E1021'""", UUID.class);
    }

    private HttpResponse<String> add(String persona, UUID timesheet, String date, String phase, String hours) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workDate", date);
        body.put("phase", phase);
        body.put("hours", new BigDecimal(hours));
        return flow.loggedInAs(persona).post("/api/v1/digital-timesheets/" + timesheet + "/lines", toJson(body));
    }

    private static void assertRefused(HttpResponse<String> response, int status, String message) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(json(response.body()).path("detail").asText()).isEqualTo(message);
    }

    private int lineCount(UUID timesheet) {
        return jdbc.queryForObject("SELECT count(*) FROM bpa.digital_timesheet_line WHERE digital_timesheet_id = ?", Integer.class, timesheet);
    }

    private String lineStatus(UUID line) {
        return jdbc.queryForObject("SELECT status FROM bpa.digital_timesheet_line WHERE id = ?", String.class, line);
    }

    private BigDecimal lineHours(UUID line) {
        return jdbc.queryForObject("SELECT hours FROM bpa.digital_timesheet_line WHERE id = ?", BigDecimal.class, line);
    }

    /** The supervisor sends the hours: completes the open Book hours task of the instance. */
    private void send(long instance) {
        HttpResponse<String> sent = flow.loggedInAs("supervisor").post("/api/v1/tasks/" + awaitTask(instance, BOOK_HOURS) + "/complete", toJson(Map.of("variables", Map.of())));
        assertThat(sent.statusCode()).as("send: " + sent.body()).isEqualTo(204);
    }

    private HttpResponse<String> decide(String user, String task, String decision, String reason) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("hoursDecision", decision);
        if (reason != null) {
            variables.put("hoursReason", reason);
        }
        return flow.loggedInAs(user).post("/api/v1/tasks/" + task + "/complete", toJson(Map.of("variables", variables)));
    }

    private String awaitTask(long instance, String elementId) {
        return poll(() -> openTasks(instance, elementId).stream().findFirst(), found -> found.isPresent(), elementId + " of " + instance).orElseThrow()
                .path("userTaskKey").asText();
    }

    private long startAtBookHours(String workpackNumber) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("workpackId", jdbc.queryForObject("SELECT id FROM bpa.workpack WHERE workpack_number = ?", UUID.class, workpackNumber).toString());
        variables.put("workpackNumber", workpackNumber);
        Reply started = IntegrationApp.admin.post("/v2/process-instances", Map.of(
                "processDefinitionId", PROCESS, "variables", variables, "startInstructions", List.of(Map.of("elementId", BOOK_HOURS))));
        assertThat(started.status()).as("start at " + BOOK_HOURS + ": " + started.body()).isEqualTo(200);
        return Long.parseLong(started.body().path("processInstanceKey").asText());
    }
}
