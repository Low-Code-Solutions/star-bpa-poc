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
 * BPA-51 (E2-04): the phase budget and the daily limit of {@code Decision_HoursWithinBudget}, at save and at send. One test per Gherkin scenario,
 * named as in the Jira Definition of Done (the rule-order scenario has no name there, so {@code BPA51_dailyLimitIsCheckedBeforeThePhaseBudget} is
 * ours, plan Q7); Scenario Outline rows run inside their test. Today is 18 Nov 2026 (Asia/Riyadh). The Background is BPA-50's: WP-000457 is
 * Released with Cutting 300 h and Welding 200 h and E1021 and E1099 are assigned ({@link BookHoursFixture}); a Given "has N hours" is lines inserted
 * by SQL. The save-time tests call the lines API as the supervisor; the send-time test starts Process_PPC_TimesheetToProgress at UserTask_BookHours and
 * completes that task through the task API.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(AssignmentFileApiTest.TestClockConfig.class)
class HoursBudgetApiTest {

    static final Instant TODAY = Instant.parse("2026-11-18T06:00:00Z"); // 09:00 in Riyadh
    static final LocalDate D17 = LocalDate.of(2026, 11, 17);
    static final String PROCESS = "Process_PPC_TimesheetToProgress";
    static final String BOOK_HOURS = "UserTask_BookHours";

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

    private BookHoursFixture fixture;
    private BookHoursFixture.Ids ids;
    private AssignmentFlow flow;

    /** All test classes share one Camunda: no Book hours task left waiting by another class may mix with this class's instances. */
    @BeforeAll
    static void noBookHoursTaskLeftWaiting() {
        IntegrationApp.background();
        CamundaSteps.cancelInstancesWaitingAt(BOOK_HOURS);
    }

    @BeforeEach
    void background() {
        clock.set(TODAY);
        fixture = new BookHoursFixture(jdbc);
        ids = fixture.reset();
        flow = new AssignmentFlow(port, jdbc);
    }

    // --- the scenarios ----------------------------------------------------------------------------------------------------

    @Test
    void BPA51_phaseBudgetReachedExactlyIsAllowed() {
        fixture.fill(ids.timesheet("E1099"), "Cutting", new BigDecimal("292"), "Draft");

        HttpResponse<String> saved = add("E1021", "2026-11-17", "Cutting", "8");

        assertThat(saved.statusCode()).as("292 + 8 = 300 h, exactly the budget: " + saved.body()).isEqualTo(201);
    }

    @Test
    void BPA51_phaseBudgetExceededIsBlocked() {
        fixture.fill(ids.timesheet("E1099"), "Cutting", new BigDecimal("292"), "Draft");

        HttpResponse<String> blocked = add("E1021", "2026-11-17", "Cutting", "8.01");

        assertRefused(blocked, "Phase Cutting budget of 300 h would be exceeded");
        assertThat(lineCount("E1021")).isZero();
    }

    @Test
    void BPA51_otherWorkersHoursCountTowardsBudget() {
        fixture.fill(ids.timesheet("E1099"), "Cutting", new BigDecimal("295"), "Draft");

        assertRefused(add("E1021", "2026-11-17", "Cutting", "6"), "Phase Cutting budget of 300 h would be exceeded");
    }

    @Test
    void BPA51_rejectedLinesDoNotCount() {
        // 295 h on Cutting, of which 10 h are Rejected: 285 h count, so 15 h more reach exactly 300 h
        fixture.fill(ids.timesheet("E1099"), "Cutting", new BigDecimal("285"), "Draft");
        fixture.line(ids.timesheet("E1099"), LocalDate.of(2026, 9, 1), "Cutting", new BigDecimal("10"), "Rejected");

        HttpResponse<String> saved = add("E1021", "2026-11-17", "Cutting", "15");

        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(201);
    }

    @Test
    void BPA51_dailyLimitReachedExactlyIsAllowed() {
        UUID eight = fixture.line(ids.timesheet("E1021"), D17, "Cutting", new BigDecimal("8"), "Draft");
        assertThat(eight).isNotNull();

        HttpResponse<String> sixteen = add("E1021", "2026-11-17", "Welding", "8");
        assertThat(sixteen.statusCode()).as("8 + 8 = 16 h, exactly the limit: " + sixteen.body()).isEqualTo(201);

        // the second row of the outline starts again from 8 h
        jdbc.update("DELETE FROM bpa.digital_timesheet_line WHERE digital_timesheet_id = ? AND phase = 'Welding'", ids.timesheet("E1021"));
        assertRefused(add("E1021", "2026-11-17", "Welding", "8.25"), "Worker E1021 would exceed 16 hours on 17 Nov 2026");
    }

    @Test
    void BPA51_dailyLimitCountsAllWorkPacks() {
        UUID other = fixture.secondWorkPackTimesheetOf("E1021", "WP-000461");
        fixture.line(other, D17, "Cutting", new BigDecimal("10"), "Draft");

        HttpResponse<String> blocked = add("E1021", "2026-11-17", "Cutting", "7");

        assertRefused(blocked, "Worker E1021 would exceed 16 hours on 17 Nov 2026");
        assertThat(lineCount("E1021")).isZero();
    }

    @Test
    void BPA51_dailyLimitIsCheckedBeforeThePhaseBudget() {
        // both limits break: E1021 has 10 h that day (Welding) and Cutting is at 299 h; 7 h more is 17 h and 306 h
        fixture.line(ids.timesheet("E1021"), D17, "Welding", new BigDecimal("10"), "Draft");
        fixture.fill(ids.timesheet("E1099"), "Cutting", new BigDecimal("299"), "Draft");

        assertRefused(add("E1021", "2026-11-17", "Cutting", "7"), "Worker E1021 would exceed 16 hours on 17 Nov 2026");
    }

    @Test
    void BPA51_changedLineReplacesOldHours() {
        // Cutting is at 300 h including E1021's 8 h line: changing it to 7 h must replace the 8 h, not add to them
        fixture.fill(ids.timesheet("E1099"), "Cutting", new BigDecimal("292"), "Draft");
        UUID line = fixture.line(ids.timesheet("E1021"), D17, "Cutting", new BigDecimal("8"), "Draft");

        HttpResponse<String> changed = flow.loggedInAs("supervisor").put("/api/v1/digital-timesheet-lines/" + line, toJson(Map.of("hours", new BigDecimal("7"))));

        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(200);
        assertThat(json(changed.body()).path("hours").decimalValue()).isEqualByComparingTo("7");
    }

    @Test
    void BPA51_sendRechecksTheBudget() {
        // E1021 has a Draft line of 8 h on Cutting; the budget is cut to 5 h since it was saved (only a test can do that, SOR Notes)
        UUID line = fixture.line(ids.timesheet("E1021"), D17, "Cutting", new BigDecimal("8"), "Draft");
        jdbc.update("UPDATE bpa.workpack_phase SET manhours = 5 WHERE workpack_id = ? AND phase = 'Cutting'", ids.workpackId());
        long instance = startAtBookHours();
        String task = CamundaSteps.awaitOpenTask(instance, BOOK_HOURS).path("userTaskKey").asText();

        HttpResponse<String> sent = flow.loggedInAs("supervisor").post("/api/v1/tasks/" + task + "/complete", toJson(Map.of("variables", Map.of())));
        assertThat(sent.statusCode()).as("send: " + sent.body()).isEqualTo(204);

        // the instance returns to UserTask_BookHours: a new task of the same instance
        JsonNode back = poll(() -> openTasks(instance, BOOK_HOURS).stream().filter(t -> !task.equals(t.path("userTaskKey").asText())).findFirst(),
                found -> found.isPresent(), "UserTask_BookHours of " + instance + " again after the send").orElseThrow();
        HttpResponse<String> reopened = flow.loggedInAs("supervisor").get("/api/v1/tasks/" + back.path("userTaskKey").asText());
        assertThat(reopened.statusCode()).as(reopened.body()).isEqualTo(200);
        List<String> messages = new ArrayList<>();
        json(reopened.body()).path("validationMessages").forEach(m -> messages.add(m.asText()));
        assertThat(messages).contains("Phase Cutting budget of 5 h would be exceeded");
        assertThat(jdbc.queryForObject("SELECT status FROM bpa.digital_timesheet_line WHERE id = ?", String.class, line)).as("the line stays Draft").isEqualTo("Draft");
    }

    // --- not scenarios ---------------------------------------------------------------------------------------------------------

    @Test
    void sendingWithNoDraftOrReturnedLineReturnsToBookHoursWithAMessage() {
        // ASSUMPTION: the text is the BPMN documentation's example, not a story's
        fixture.line(ids.timesheet("E1021"), D17, "Cutting", new BigDecimal("8"), "Submitted");
        long instance = startAtBookHours();
        String task = CamundaSteps.awaitOpenTask(instance, BOOK_HOURS).path("userTaskKey").asText();

        assertThat(flow.loggedInAs("supervisor").post("/api/v1/tasks/" + task + "/complete", toJson(Map.of("variables", Map.of()))).statusCode()).isEqualTo(204);

        JsonNode back = poll(() -> openTasks(instance, BOOK_HOURS).stream().filter(t -> !task.equals(t.path("userTaskKey").asText())).findFirst(),
                found -> found.isPresent(), "UserTask_BookHours of " + instance + " again after the send").orElseThrow();
        JsonNode reopened = json(flow.loggedInAs("supervisor").get("/api/v1/tasks/" + back.path("userTaskKey").asText()).body());
        List<String> messages = new ArrayList<>();
        reopened.path("validationMessages").forEach(m -> messages.add(m.asText()));
        assertThat(messages).containsExactly("No Draft or Returned hours to send for WP-000457");
    }

    // --- helpers ----------------------------------------------------------------------------------------------------------

    /** Starts Process_PPC_TimesheetToProgress at UserTask_BookHours for WP-000457, as BPA-49's message would have (the workpackId and workpackNumber it carries). */
    private long startAtBookHours() {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("workpackId", ids.workpackId().toString());
        variables.put("workpackNumber", BookHoursFixture.WORKPACK);
        Reply started = IntegrationApp.admin.post("/v2/process-instances", Map.of(
                "processDefinitionId", PROCESS, "variables", variables, "startInstructions", List.of(Map.of("elementId", BOOK_HOURS))));
        assertThat(started.status()).as("start at " + BOOK_HOURS + ": " + started.body()).isEqualTo(200);
        return Long.parseLong(started.body().path("processInstanceKey").asText());
    }

    private HttpResponse<String> add(String badge, String date, String phase, String hours) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workDate", date);
        body.put("phase", phase);
        body.put("hours", new BigDecimal(hours));
        return flow.loggedInAs("supervisor").post("/api/v1/digital-timesheets/" + ids.timesheet(badge) + "/lines", toJson(body));
    }

    private static void assertRefused(HttpResponse<String> response, String message) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(json(response.body()).path("detail").asText()).isEqualTo(message);
    }

    private int lineCount(String badge) {
        return jdbc.queryForObject("SELECT count(*) FROM bpa.digital_timesheet_line WHERE digital_timesheet_id = ?", Integer.class, ids.timesheet(badge));
    }
}
