package com.lcs.star.bpa;

import static com.lcs.star.bpa.CamundaSteps.json;
import static com.lcs.star.bpa.CamundaSteps.openTasks;
import static com.lcs.star.bpa.CamundaSteps.poll;
import static com.lcs.star.bpa.CamundaSteps.toJson;
import static com.lcs.star.bpa.TimesheetFileBuilder.timesheet;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.lcs.star.bpa.CamundaRest.Reply;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.Instant;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BPA-75 (E2-22): a line for a Worker who has approved leave ({@code worker_leave}, leave_from to leave_to inclusive) on the work date is refused at save
 * (form, upload row) and stops the send when the leave was approved after the line was saved. One test per Gherkin scenario, named as in the Jira
 * Definition of Done; the extras are ours (plan BPA-75, Questions 1 to 6). Today is 19 Nov 2026 09:00 (Asia/Riyadh), so 16 to 19 Nov are in the open
 * week and none is in the future. The tests insert their own leave rows (E1099 on 18 Nov 2026) and do not depend on the seeded leave of E1203.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(AssignmentFileApiTest.TestClockConfig.class)
class WorkerLeaveApiTest {

    static final Instant TODAY = Instant.parse("2026-11-19T06:00:00Z"); // 09:00 in Riyadh
    static final String PROCESS = "Process_PPC_TimesheetToProgress";
    static final String BOOK_HOURS = "UserTask_BookHours";
    static final String VALIDATE = "UserTask_ValidateHours";
    static final String PIP01 = "CBS-139-PIP-01";

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

    /** All test classes share one Camunda: no Book hours or Validate task left waiting by another class may mix with this class's instances. */
    @BeforeAll
    static void noTaskLeftWaiting() {
        IntegrationApp.background();
        CamundaSteps.cancelInstancesWaitingAt(BOOK_HOURS);
        CamundaSteps.cancelInstancesWaitingAt(VALIDATE);
    }

    @BeforeEach
    void background() {
        clock.set(TODAY);
        fixture = new BookHoursFixture(jdbc);
        ids = fixture.reset();
        fixture.fmsBackground(ids); // WP-000457 with CBS-139-PIP-01 on E1021 and E1099
        flow = new AssignmentFlow(port, jdbc);
        jdbc.update("DELETE FROM bpa.worker_leave"); // the tests insert their own leave; the seeded one of E1203 is not used
        jdbc.update("DELETE FROM bpa.cbs_block WHERE block_type <> 'FmsTimesheet'");
        jdbc.update("UPDATE bpa.cbs_block SET is_closed = false WHERE cbs_code_ref = ? AND block_type = 'FmsTimesheet'", PIP01);
    }

    @AfterEach
    void openPip01Again() {
        jdbc.update("UPDATE bpa.cbs_block SET is_closed = false WHERE cbs_code_ref = ? AND block_type = 'FmsTimesheet'", PIP01);
        jdbc.update("DELETE FROM bpa.worker_leave");
    }

    // --- the scenarios ----------------------------------------------------------------------------------------------------

    @Test
    void BPA75_bookingOnLeaveDayIsBlocked() {
        leave("E1099", "2026-11-18", "2026-11-18");

        HttpResponse<String> response = add("E1099", "2026-11-18", "Cutting", "8.00");

        assertRefused(response, "Worker E1099 is on leave on 18 Nov 2026");
        assertThat(lineCount("E1099")).as("the line is not saved").isZero();
    }

    @Test
    void BPA75_bookingOnWorkingDayIsAccepted() {
        leave("E1099", "2026-11-18", "2026-11-18");

        HttpResponse<String> response = add("E1099", "2026-11-17", "Cutting", "8.00");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(json(response.body()).path("status").asText()).isEqualTo("Draft");
        assertThat(lineCount("E1099")).isEqualTo(1);
    }

    @Test
    void BPA75_uploadRowOnLeaveDayIsRejected() {
        leave("E1099", "2026-11-18", "2026-11-18");

        HttpResponse<String> response = flow.loggedInAs("production-team").postFile("/api/v1/timesheet-files", "leave.xlsx",
                timesheet().row("WP-000457", "E1099", "18/11/2026", "Cutting", 8).row("WP-000457", "E1021", "18/11/2026", "Cutting", 8).xlsx());

        List<String> outcomes = outcomes(response);
        assertThat(outcomes.get(0)).as("E1099's row").isEqualTo("Worker E1099 is on leave on 18 Nov 2026");
        assertThat(outcomes.get(1)).as("E1021 row is saved").isEqualTo("Saved");
        assertThat(lineCount("E1099")).as("no line for E1099").isZero();
        assertThat(lineCount("E1021")).as("the other valid row is saved").isEqualTo(1);
    }

    /**
     * Q2 (plan BPA-75): worker_leave holds approved leave only (Q-DM23), so a leave request that is not approved cannot be stored. The test can only show that
     * a Worker with no leave row is allowed; it passes before the code, as a guard against a check that is too wide.
     */
    @Test
    void BPA75_leaveNotYetApprovedDoesNotBlock() {
        HttpResponse<String> response = add("E1021", "2026-11-19", "Cutting", "8.00");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(json(response.body()).path("status").asText()).isEqualTo("Draft");
    }

    // --- not scenarios (plan BPA-75, Questions 3 and 5) -----------------------------------------------------------------------------

    @Test
    void leaveFromAndLeaveToAreBothBlocked() {
        leave("E1099", "2026-11-17", "2026-11-18");

        assertRefused(add("E1099", "2026-11-17", "Cutting", "8"), "Worker E1099 is on leave on 17 Nov 2026");
        assertRefused(add("E1099", "2026-11-18", "Cutting", "8"), "Worker E1099 is on leave on 18 Nov 2026");
        assertThat(lineCount("E1099")).isZero();
    }

    @Test
    void theDaysEitherSideAreAllowed() {
        leave("E1099", "2026-11-17", "2026-11-18");

        assertThat(add("E1099", "2026-11-16", "Cutting", "8").statusCode()).as("the day before leave_from").isEqualTo(201);
        assertThat(add("E1099", "2026-11-19", "Cutting", "8").statusCode()).as("the day after leave_to").isEqualTo(201);
    }

    @Test
    void leaveApprovedAfterSavingStopsTheSend() {
        HttpResponse<String> saved = add("E1099", "2026-11-18", "Cutting", "8");
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(201);
        UUID line = UUID.fromString(json(saved.body()).path("id").asText());
        leave("E1099", "2026-11-18", "2026-11-18");
        long instance = startAtBookHours();
        String task = CamundaSteps.awaitOpenTask(instance, BOOK_HOURS).path("userTaskKey").asText();

        HttpResponse<String> sent = flow.loggedInAs("supervisor").post("/api/v1/tasks/" + task + "/complete", toJson(Map.of("variables", Map.of())));
        assertThat(sent.statusCode()).as("send: " + sent.body()).isEqualTo(204);

        JsonNode back = poll(() -> openTasks(instance, BOOK_HOURS).stream().filter(t -> !task.equals(t.path("userTaskKey").asText())).findFirst(),
                found -> found.isPresent(), "UserTask_BookHours of " + instance + " again after the send").orElseThrow();
        assertThat(messagesOf(back.path("userTaskKey").asText())).contains("Worker E1099 is on leave on 18 Nov 2026");
        assertThat(jdbc.queryForObject("SELECT status FROM bpa.digital_timesheet_line WHERE id = ?", String.class, line)).as("the line stays Draft").isEqualTo("Draft");
    }

    @Test
    void aChangeOnALeaveDayIsRefused() {
        HttpResponse<String> saved = add("E1099", "2026-11-18", "Cutting", "8");
        UUID line = UUID.fromString(json(saved.body()).path("id").asText());
        leave("E1099", "2026-11-18", "2026-11-18");

        HttpResponse<String> changed = flow.loggedInAs("supervisor").put("/api/v1/digital-timesheet-lines/" + line, toJson(Map.of("hours", new BigDecimal("6"))));

        assertRefused(changed, "Worker E1099 is on leave on 18 Nov 2026");
        assertThat(jdbc.queryForObject("SELECT hours FROM bpa.digital_timesheet_line WHERE id = ?", BigDecimal.class, line)).isEqualByComparingTo("8");
    }

    @Test
    void anAmendOnALeaveDayIsRefused() {
        HttpResponse<String> saved = add("E1099", "2026-11-18", "Cutting", "8");
        UUID line = UUID.fromString(json(saved.body()).path("id").asText());
        long instance = startAtBookHours();
        String task = CamundaSteps.awaitOpenTask(instance, BOOK_HOURS).path("userTaskKey").asText();
        assertThat(flow.loggedInAs("supervisor").post("/api/v1/tasks/" + task + "/complete", toJson(Map.of("variables", Map.of()))).statusCode()).isEqualTo(204);
        CamundaSteps.awaitOpenTask(instance, VALIDATE);
        leave("E1099", "2026-11-18", "2026-11-18");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("hours", new BigDecimal("7"));
        body.put("reason", "Supervisor request");
        HttpResponse<String> amended = flow.loggedInAs("timekeeper").post("/api/v1/digital-timesheet-lines/" + line + "/amendments", toJson(body));

        assertRefused(amended, "Worker E1099 is on leave on 18 Nov 2026");
        assertThat(jdbc.queryForObject("SELECT hours FROM bpa.digital_timesheet_line WHERE id = ?", BigDecimal.class, line)).isEqualByComparingTo("8");
    }

    /** DMN order (ASSUMPTION there): a closed CBS comes before leave, so a line that is both gets the CBS text. */
    @Test
    void theCbsCheckComesBeforeLeave() {
        leave("E1099", "2026-11-18", "2026-11-18");
        jdbc.update("UPDATE bpa.cbs_block SET is_closed = true WHERE cbs_code_ref = ? AND block_type = 'FmsTimesheet'", PIP01);

        assertRefused(add("E1099", "2026-11-18", "Cutting", "8"), "CBS CBS-139-PIP-01 is closed for productive hours");
    }

    @Test
    void anotherWorkersLeaveDoesNotBlock() {
        leave("E1021", "2026-11-18", "2026-11-18");

        HttpResponse<String> response = add("E1099", "2026-11-18", "Cutting", "8");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    }

    // --- helpers ----------------------------------------------------------------------------------------------------------

    /** Approved leave for a Worker, both dates inclusive (worker_leave has no status: only approved leave is stored, Q-DM23). */
    private void leave(String badge, String from, String to) {
        jdbc.update("""
                INSERT INTO bpa.worker_leave (worker_id, leave_from, leave_to, created_by, updated_by)
                SELECT w.id, DATE '%s', DATE '%s', 'test', 'test' FROM bpa.worker w WHERE w.employee_ref = ?""".formatted(from, to), badge);
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

    private static List<String> outcomes(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        List<String> outcomes = new ArrayList<>();
        json(response.body()).path("rows").forEach(r -> outcomes.add(r.path("outcome").asText()));
        return outcomes;
    }

    private int lineCount(String badge) {
        return jdbc.queryForObject("SELECT count(*) FROM bpa.digital_timesheet_line WHERE digital_timesheet_id = ?", Integer.class, ids.timesheet(badge));
    }

    private List<String> messagesOf(String taskKey) {
        List<String> messages = new ArrayList<>();
        json(flow.loggedInAs("supervisor").get("/api/v1/tasks/" + taskKey).body()).path("validationMessages").forEach(m -> messages.add(m.asText()));
        return messages;
    }

    /** Starts Process_PPC_TimesheetToProgress at UserTask_BookHours for WP-000457, as BPA-49's message would have. */
    private long startAtBookHours() {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("workpackId", ids.workpackId().toString());
        variables.put("workpackNumber", BookHoursFixture.WORKPACK);
        Reply started = IntegrationApp.admin.post("/v2/process-instances", Map.of(
                "processDefinitionId", PROCESS, "variables", variables, "startInstructions", List.of(Map.of("elementId", BOOK_HOURS))));
        assertThat(started.status()).as("start at " + BOOK_HOURS + ": " + started.body()).isEqualTo(200);
        return Long.parseLong(started.body().path("processInstanceKey").asText());
    }
}
