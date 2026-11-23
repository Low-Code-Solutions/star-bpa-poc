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
 * BPA-69 (E2-21): a line whose time sheet carries a CBS that is closed for productive hours ({@code cbs_block}, block type FmsTimesheet) is refused at
 * save (form, upload row) and stops the send when the CBS was closed after the line was saved. One test per Gherkin scenario, named as in the Jira
 * Definition of Done; the extras are ours (plan BPA-69, Questions 1 to 4). Today is 18 Nov 2026 (Asia/Riyadh). The Background is built here: WP-000490
 * (not seeded) with E1021 assigned and a time sheet carrying CBS-139-PIP-99 (closed in the seed); WP-000457 carries CBS-139-PIP-01 (open) on E1021 and
 * E1099; WP-000470 has a time sheet for E1150 with no CBS (D40). Tests that close CBS-139-PIP-01 open it again afterwards.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(AssignmentFileApiTest.TestClockConfig.class)
class CbsBlockApiTest {

    static final Instant TODAY = Instant.parse("2026-11-18T06:00:00Z"); // 09:00 in Riyadh
    static final String PROCESS = "Process_PPC_TimesheetToProgress";
    static final String BOOK_HOURS = "UserTask_BookHours";
    static final String VALIDATE = "UserTask_ValidateHours";
    static final String PIP01 = "CBS-139-PIP-01";
    static final String PIP99 = "CBS-139-PIP-99";
    static final String PIP99_CLOSED = "CBS CBS-139-PIP-99 is closed for productive hours";
    static final String PIP01_CLOSED = "CBS CBS-139-PIP-01 is closed for productive hours";

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
    private BookHoursFixture.FmsIds sheets;
    private AssignmentFlow flow;
    private UUID wp490Sheet;

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
        sheets = fixture.fmsBackground(ids); // CBS-139-PIP-01 on E1021 and E1099 (WP-000457); WP-000470 with a time sheet for E1150 and no CBS
        flow = new AssignmentFlow(port, jdbc);
        assignE1150ToWp470();
        wp490Sheet = workPack490();
        // cbs_block as the seed has it: PIP-01 open, PIP-99 closed, no row of another block type
        jdbc.update("DELETE FROM bpa.cbs_block WHERE block_type <> 'FmsTimesheet'");
        jdbc.update("UPDATE bpa.cbs_block SET is_closed = false WHERE cbs_code_ref = ? AND block_type = 'FmsTimesheet'", PIP01);
        jdbc.update("UPDATE bpa.cbs_block SET is_closed = true WHERE cbs_code_ref = ? AND block_type = 'FmsTimesheet'", PIP99);
    }

    @AfterEach
    void openPip01Again() {
        jdbc.update("DELETE FROM bpa.cbs_block WHERE block_type <> 'FmsTimesheet'");
        jdbc.update("UPDATE bpa.cbs_block SET is_closed = false WHERE cbs_code_ref = ? AND block_type = 'FmsTimesheet'", PIP01);
    }

    // --- the scenarios ----------------------------------------------------------------------------------------------------

    @Test
    void BPA69_hoursOnClosedCbsAreRefused() {
        HttpResponse<String> response = add(wp490Sheet, "2026-11-17", "Cutting", "8");

        assertRefused(response, PIP99_CLOSED);
        assertThat(lineCount(wp490Sheet)).as("no line saved").isZero();
    }

    @Test
    void BPA69_uploadRowOnClosedCbsIsRejected() {
        HttpResponse<String> response = flow.loggedInAs("supervisor").postFile("/api/v1/timesheet-files", "closed-cbs.xlsx",
                timesheet().row("WP-000490", "E1021", "17/11/2026", "Cutting", 4).xlsx());

        assertThat(outcomes(response)).containsExactly(PIP99_CLOSED);
        assertThat(lineCount(wp490Sheet)).as("no line saved").isZero();
    }

    @Test
    void BPA69_noCbsIsNeverBlocked() {
        HttpResponse<String> response = add(sheets.e1150OnWp470(), "2026-11-17", "Welding", "8");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(lineCount(sheets.e1150OnWp470())).isEqualTo(1);
    }

    @Test
    void BPA69_openCbsAcceptsHours() {
        HttpResponse<String> response = add(ids.timesheet("E1021"), "2026-11-17", "Cutting", "8");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(lineCount(ids.timesheet("E1021"))).isEqualTo(1);
    }

    @Test
    void BPA69_cbsClosedAfterSavingStopsTheSend() {
        HttpResponse<String> saved = add(ids.timesheet("E1021"), "2026-11-17", "Cutting", "8");
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(201);
        UUID line = UUID.fromString(json(saved.body()).path("id").asText());
        closePip01();
        long instance = startAtBookHours();
        String task = CamundaSteps.awaitOpenTask(instance, BOOK_HOURS).path("userTaskKey").asText();

        HttpResponse<String> sent = flow.loggedInAs("supervisor").post("/api/v1/tasks/" + task + "/complete", toJson(Map.of("variables", Map.of())));
        assertThat(sent.statusCode()).as("send: " + sent.body()).isEqualTo(204);

        JsonNode back = poll(() -> openTasks(instance, BOOK_HOURS).stream().filter(t -> !task.equals(t.path("userTaskKey").asText())).findFirst(),
                found -> found.isPresent(), "UserTask_BookHours of " + instance + " again after the send").orElseThrow();
        assertThat(messagesOf(back.path("userTaskKey").asText())).contains(PIP01_CLOSED);
        assertThat(jdbc.queryForObject("SELECT status FROM bpa.digital_timesheet_line WHERE id = ?", String.class, line)).as("the line stays Draft").isEqualTo("Draft");
    }

    // --- not scenarios (plan BPA-69, Questions 1 to 4) -----------------------------------------------------------------------------

    @Test
    void aChangeOnAClosedCbsIsRefused() {
        HttpResponse<String> saved = add(ids.timesheet("E1021"), "2026-11-17", "Cutting", "8");
        UUID line = UUID.fromString(json(saved.body()).path("id").asText());
        closePip01();

        HttpResponse<String> changed = flow.loggedInAs("supervisor").put("/api/v1/digital-timesheet-lines/" + line, toJson(Map.of("hours", new BigDecimal("6"))));

        assertRefused(changed, PIP01_CLOSED);
        assertThat(jdbc.queryForObject("SELECT hours FROM bpa.digital_timesheet_line WHERE id = ?", BigDecimal.class, line)).isEqualByComparingTo("8");
    }

    @Test
    void anAmendOnAClosedCbsIsRefused() {
        HttpResponse<String> saved = add(ids.timesheet("E1021"), "2026-11-17", "Cutting", "8");
        UUID line = UUID.fromString(json(saved.body()).path("id").asText());
        long instance = startAtBookHours();
        String task = CamundaSteps.awaitOpenTask(instance, BOOK_HOURS).path("userTaskKey").asText();
        assertThat(flow.loggedInAs("supervisor").post("/api/v1/tasks/" + task + "/complete", toJson(Map.of("variables", Map.of()))).statusCode()).isEqualTo(204);
        CamundaSteps.awaitOpenTask(instance, VALIDATE);
        closePip01();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("hours", new BigDecimal("7"));
        body.put("reason", "Supervisor request");
        HttpResponse<String> amended = flow.loggedInAs("timekeeper").post("/api/v1/digital-timesheet-lines/" + line + "/amendments", toJson(body));

        assertRefused(amended, PIP01_CLOSED);
        assertThat(jdbc.queryForObject("SELECT hours FROM bpa.digital_timesheet_line WHERE id = ?", BigDecimal.class, line)).isEqualByComparingTo("8");
    }

    @Test
    void theCbsCheckComesBeforeTheDailyLimit() {
        HttpResponse<String> response = add(wp490Sheet, "2026-11-17", "Cutting", "17");

        assertRefused(response, PIP99_CLOSED);
    }

    @Test
    void anotherBlockTypeDoesNotBlock() {
        jdbc.update("""
                INSERT INTO bpa.cbs_block (cbs_code_ref, block_type, is_closed, created_by, updated_by) VALUES (?, 'DigitalTimesheet', true, 'test', 'test')""", PIP01);

        HttpResponse<String> response = add(ids.timesheet("E1021"), "2026-11-17", "Cutting", "8");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    }

    // --- the Background ------------------------------------------------------------------------------------------------------------

    /** WP-000470's time sheet for E1150 (no CBS) is assigned, so a line can be booked on it; fmsBackground leaves it unassigned. */
    private void assignE1150ToWp470() {
        jdbc.update("""
                INSERT INTO bpa.worker_assignment (workpack_id, worker_id, estimated_hours, assignment_file_id, created_by, updated_by)
                SELECT w.id, k.id, 100, (SELECT id FROM bpa.assignment_file LIMIT 1), 'test', 'test'
                FROM bpa.workpack w, bpa.worker k WHERE w.workpack_number = 'WP-000470' AND k.employee_ref = 'E1150'""");
    }

    /** WP-000490 (not seeded): A-2201, Cutting 300 h, E1021 assigned, a time sheet carrying CBS-139-PIP-99; returns that time sheet. */
    private UUID workPack490() {
        UUID workpack = jdbc.queryForObject("""
                INSERT INTO bpa.workpack (workpack_number, activity_ref, drawing_ref, department_ref, completion_date, status, assignment_state,
                                          process_instance_key, created_by, updated_by)
                VALUES ('WP-000490', 'A-2201', 'DWG-118', 'Fabrication', DATE '2026-11-30', 'Released', 'Assigned', 9000000090, 'test', 'test')
                RETURNING id""", UUID.class);
        jdbc.update("INSERT INTO bpa.workpack_phase (workpack_id, phase, manhours, created_by, updated_by) VALUES (?, 'Cutting', 300, 'test', 'test')", workpack);
        UUID header = jdbc.queryForObject("""
                INSERT INTO bpa.hour_journal_header (workpack_id, estimated_manhours, created_by, updated_by) VALUES (?, 300, 'test', 'test') RETURNING id""",
                UUID.class, workpack);
        UUID worker = jdbc.queryForObject("SELECT id FROM bpa.worker WHERE employee_ref = 'E1021'", UUID.class);
        UUID journalLine = jdbc.queryForObject("""
                INSERT INTO bpa.hour_journal_line (header_id, worker_id, role, designation, estimated_hours, created_by, updated_by)
                SELECT ?, w.id, w.role, w.designation, 100, 'test', 'test' FROM bpa.worker w WHERE w.id = ? RETURNING id""", UUID.class, header, worker);
        jdbc.update("""
                INSERT INTO bpa.worker_assignment (workpack_id, worker_id, estimated_hours, assignment_file_id, created_by, updated_by)
                VALUES (?, ?, 100, (SELECT id FROM bpa.assignment_file LIMIT 1), 'test', 'test')""", workpack, worker);
        return jdbc.queryForObject("""
                INSERT INTO bpa.digital_timesheet (worker_id, workpack_id, journal_line_id, project_ref, wbs_ref, activity_ref, cbs_code_ref, created_by, updated_by)
                VALUES (?, ?, ?, 'PRJ-139', '11031', 'A-2201', ?, 'test', 'test') RETURNING id""", UUID.class, worker, workpack, journalLine, PIP99);
    }

    private void closePip01() {
        jdbc.update("UPDATE bpa.cbs_block SET is_closed = true WHERE cbs_code_ref = ? AND block_type = 'FmsTimesheet'", PIP01);
    }

    // --- helpers ----------------------------------------------------------------------------------------------------------

    private HttpResponse<String> add(UUID timesheet, String date, String phase, String hours) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workDate", date);
        body.put("phase", phase);
        body.put("hours", new BigDecimal(hours));
        return flow.loggedInAs("supervisor").post("/api/v1/digital-timesheets/" + timesheet + "/lines", toJson(body));
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

    private int lineCount(UUID timesheet) {
        return jdbc.queryForObject("SELECT count(*) FROM bpa.digital_timesheet_line WHERE digital_timesheet_id = ?", Integer.class, timesheet);
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
