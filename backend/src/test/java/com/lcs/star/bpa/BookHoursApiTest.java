package com.lcs.star.bpa;

import static com.lcs.star.bpa.CamundaSteps.json;
import static com.lcs.star.bpa.CamundaSteps.toJson;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
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
 * BPA-50 (E2-03): daily hour lines on Digital Time sheets. One test per Gherkin scenario, named as in the Jira Definition of Done; Scenario
 * Outline rows run inside their one test. Today is 18 Nov 2026 (Asia/Riyadh) and the week ending 12 Nov 2026 is cut off; the Background is made
 * with SQL by {@link BookHoursFixture}. Calls go through the API as the persona, like the portal. The text of every message is the story's.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(AssignmentFileApiTest.TestClockConfig.class)
class BookHoursApiTest {

    static final Instant TODAY = Instant.parse("2026-11-18T06:00:00Z"); // 09:00 in Riyadh
    static final LocalDate D17 = LocalDate.of(2026, 11, 17);

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

    @BeforeAll
    static void camundaIsUp() {
        IntegrationApp.background();
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
    void BPA50_supervisorBooksALine() {
        HttpResponse<String> saved = add("supervisor", "E1021", "2026-11-17", "Cutting", "8", null);

        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(201);
        Map<String, Object> row = jdbc.queryForMap("SELECT status, entry_source FROM bpa.digital_timesheet_line WHERE digital_timesheet_id = ?",
                ids.timesheet("E1021"));
        assertThat(row).containsEntry("status", "Draft").containsEntry("entry_source", "Form");
        JsonNode lines = json(flow.loggedInAs("supervisor").get(lines("E1021")).body()).path("lines");
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).path("workDate").asText()).isEqualTo("2026-11-17");
        assertThat(lines.get(0).path("phase").asText()).isEqualTo("Cutting");
        assertThat(lines.get(0).path("hours").decimalValue()).isEqualByComparingTo("8");
    }

    @Test
    void BPA50_firstLineSetsWorkPackInProgress() {
        assertThat(workPackStatus()).isEqualTo("Released");

        HttpResponse<String> saved = add("supervisor", "E1021", "2026-11-17", "Cutting", "8", null);

        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(201);
        assertThat(workPackStatus()).isEqualTo("In Progress");
        assertThat(jdbc.queryForList("SELECT old_value || ' > ' || new_value FROM bpa.audit_log WHERE table_name = 'workpack' AND column_name = 'status'",
                String.class)).containsExactly("Released > In Progress");
    }

    @Test
    void BPA50_earlierDayOfOpenWeekWithRemark() {
        HttpResponse<String> saved = add("supervisor", "E1099", "2026-11-16", "Welding", "7.5", "Night shift");

        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(201);
        Map<String, Object> row = jdbc.queryForMap("SELECT status, remarks FROM bpa.digital_timesheet_line WHERE digital_timesheet_id = ?",
                ids.timesheet("E1099"));
        assertThat(row).containsEntry("status", "Draft").containsEntry("remarks", "Night shift");
    }

    @Test
    void BPA50_remarkAtMost250Characters() {
        HttpResponse<String> tooLong = add("supervisor", "E1021", "2026-11-17", "Cutting", "8", "x".repeat(251));
        assertRefused(tooLong, 400, "Remarks can be at most 250 characters");
        assertThat(lineCount("E1021")).isZero();

        HttpResponse<String> boundary = add("supervisor", "E1021", "2026-11-17", "Cutting", "8", "x".repeat(250));
        assertThat(boundary.statusCode()).as(boundary.body()).isEqualTo(201);
    }

    @Test
    void BPA50_supervisorChangesADraftLine() {
        UUID line = fixture.line(ids.timesheet("E1021"), D17, "Cutting", new BigDecimal("8"), "Draft");

        HttpResponse<String> changed = flow.loggedInAs("supervisor").put("/api/v1/digital-timesheet-lines/" + line, toJson(Map.of("hours", new BigDecimal("9.5"))));

        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(200);
        assertThat(json(changed.body()).path("hours").decimalValue()).isEqualByComparingTo("9.5");
        assertThat(jdbc.queryForObject("SELECT hours FROM bpa.digital_timesheet_line WHERE id = ?", BigDecimal.class, line)).isEqualByComparingTo("9.5");
        assertThat(jdbc.queryForList("SELECT old_value || ' > ' || new_value FROM bpa.audit_log WHERE table_name = 'digital_timesheet_line' AND column_name = 'hours' AND record_id = ?",
                String.class, line)).as("one audit row for hours").containsExactly("8 > 9.5");
    }

    @Test
    void BPA50_supervisorDeletesADraftLine() {
        UUID line = fixture.line(ids.timesheet("E1021"), D17, "Cutting", new BigDecimal("8"), "Draft");

        HttpResponse<String> deleted = flow.loggedInAs("supervisor").delete("/api/v1/digital-timesheet-lines/" + line);

        assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(204);
        assertThat(lineCount("E1021")).isZero();
    }

    @Test
    void BPA50_duplicateLineIsRejected() {
        fixture.line(ids.timesheet("E1021"), D17, "Cutting", new BigDecimal("8"), "Draft");

        HttpResponse<String> second = add("supervisor", "E1021", "2026-11-17", "Cutting", "2", null);

        assertRefused(second, 400, "Hours already booked for E1021 on 17 Nov 2026 for phase Cutting");
        assertThat(lineCount("E1021")).isEqualTo(1);
    }

    @Test
    void BPA50_hoursMustBeAboveZero() {
        assertRefused(add("supervisor", "E1021", "2026-11-17", "Cutting", "0", null), 400, "Hours must be greater than 0");
        assertRefused(add("supervisor", "E1021", "2026-11-17", "Cutting", "-1", null), 400, "Hours must be greater than 0");
        HttpResponse<String> smallest = add("supervisor", "E1021", "2026-11-17", "Cutting", "0.01", null);
        assertThat(smallest.statusCode()).as(smallest.body()).isEqualTo(201);
    }

    @Test
    void BPA50_hoursTwoDecimalsOnly() {
        assertRefused(add("supervisor", "E1021", "2026-11-17", "Cutting", "7.125", null), 400, "Hours can have at most 2 decimal places");
        assertThat(lineCount("E1021")).isZero();
    }

    @Test
    void BPA50_futureDateIsRejected() {
        HttpResponse<String> today = add("supervisor", "E1021", "2026-11-18", "Cutting", "8", null);
        assertThat(today.statusCode()).as(today.body()).isEqualTo(201);

        assertRefused(add("supervisor", "E1021", "2026-11-19", "Cutting", "8", null), 400, "Work date cannot be in the future");
    }

    @Test
    void BPA50_cutOffWeekIsClosed() {
        assertRefused(add("supervisor", "E1021", "2026-11-12", "Cutting", "8", null), 400, "Week ending 12 Nov 2026 is closed");

        HttpResponse<String> firstDayOfOpenWeek = add("supervisor", "E1021", "2026-11-13", "Cutting", "8", null);
        assertThat(firstDayOfOpenWeek.statusCode()).as(firstDayOfOpenWeek.body()).isEqualTo(201);
    }

    @Test
    void BPA50_phaseNotOnWorkPackIsRejected() {
        assertRefused(add("supervisor", "E1021", "2026-11-17", "Finishing", "8", null), 400, "Phase Finishing is not on this Work Pack");
    }

    @Test
    void BPA50_workerNotAssignedIsRejected() {
        // plan Q6: E1150 has a time sheet on WP-000457 but no assignment row
        assertRefused(add("supervisor", "E1150", "2026-11-17", "Cutting", "8", null), 400, "Worker E1150 is not assigned to WP-000457");
        assertThat(lineCount("E1150")).isZero();
    }

    @Test
    void BPA50_submittedLineCannotChange() {
        UUID line = fixture.line(ids.timesheet("E1021"), D17, "Cutting", new BigDecimal("8"), "Submitted");

        HttpResponse<String> changed = flow.loggedInAs("supervisor").put("/api/v1/digital-timesheet-lines/" + line, toJson(Map.of("hours", 9)));

        assertRefused(changed, 409, "Line is not editable (status Submitted)");
        assertThat(jdbc.queryForObject("SELECT hours FROM bpa.digital_timesheet_line WHERE id = ?", BigDecimal.class, line)).isEqualByComparingTo("8");
    }

    @Test
    void BPA50_cancelledTimesheetTakesNoLines() {
        jdbc.update("UPDATE bpa.digital_timesheet SET submission_status = 'Cancelled' WHERE id = ?", ids.timesheet("E1099"));

        assertRefused(add("supervisor", "E1099", "2026-11-17", "Cutting", "8", null), 409, "Time sheet for E1099 on WP-000457 is Cancelled");
        assertThat(lineCount("E1099")).isZero();
    }

    @Test
    void BPA50_otherPersonasCannotBookHours() {
        HttpResponse<String> refused = add("timekeeper", "E1021", "2026-11-17", "Cutting", "8", null);

        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
        assertThat(lineCount("E1021")).isZero();
    }

    @Test
    void BPA50_aProductionTeamMemberCanAlsoBookHours() {
        HttpResponse<String> saved = add("production-team", "E1021", "2026-11-18", "Cutting", "8", null);

        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(201);
        Map<String, Object> row = jdbc.queryForMap("SELECT status, entry_source, created_by FROM bpa.digital_timesheet_line WHERE digital_timesheet_id = ?",
                ids.timesheet("E1021"));
        assertThat(row).containsEntry("status", "Draft").containsEntry("entry_source", "Form").containsEntry("created_by", "production-team");
    }

    @Test
    void BPA50_anOptionalReportedCompletionIsStoredForInformation() {
        Map<String, Object> body = body("2026-11-17", "Cutting", "8", null);
        body.put("reportedCompletionPct", new BigDecimal("40"));

        HttpResponse<String> saved = flow.loggedInAs("supervisor").post(lines("E1021"), toJson(body));

        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT reported_completion_pct FROM bpa.digital_timesheet_line WHERE digital_timesheet_id = ?", BigDecimal.class,
                ids.timesheet("E1021"))).isEqualByComparingTo("40.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.workpack_progress", Integer.class)).as("approved completion % is unchanged").isZero();
    }

    @Test
    void BPA50_aSubmittedOrApprovedLineCannotBeDeleted() {
        UUID line = fixture.line(ids.timesheet("E1021"), D17, "Cutting", new BigDecimal("8"), "Submitted");

        HttpResponse<String> refused = flow.loggedInAs("supervisor").delete("/api/v1/digital-timesheet-lines/" + line);

        assertRefused(refused, 409, "Line is not editable (status Submitted)");
        assertThat(lineCount("E1021")).as("the line still exists").isEqualTo(1);
    }

    // --- not scenarios: the decision stops a save (BPA-51 owns its scenarios) ------------------------------------------------

    @Test
    void decisionBlockedAnswerStopsTheSave() {
        // E1021 already has 10 h on 17 Nov in Welding; 7 more in Cutting makes 17 h, above the 16 h of Decision_HoursWithinBudget (E2-D6)
        fixture.line(ids.timesheet("E1021"), D17, "Welding", new BigDecimal("10"), "Draft");

        HttpResponse<String> blocked = add("supervisor", "E1021", "2026-11-17", "Cutting", "7", null);

        assertRefused(blocked, 400, "Worker E1021 would exceed 16 hours on 17 Nov 2026");
        assertThat(lineCount("E1021")).isEqualTo(1);
    }

    @Test
    void phaseBudgetBlocksTheSave() {
        // Welding is 200 h; E1099 already has 13 x 15 = 195 h of it on 1-13 Nov (inserted by SQL), so 6 h more for E1021 makes 201 h
        for (int day = 0; day < 13; day++) {
            fixture.line(ids.timesheet("E1099"), LocalDate.of(2026, 11, 1).plusDays(day), "Welding", new BigDecimal("15"), "Approved");
        }

        HttpResponse<String> blocked = add("supervisor", "E1021", "2026-11-17", "Welding", "6", null);

        assertRefused(blocked, 400, "Phase Welding budget of 200 h would be exceeded");
        assertThat(lineCount("E1021")).isZero();
    }

    // --- not scenarios: the rest of the rules and of the contract (QA review) ----------------------------------------------------

    @Test
    void otherPersonasCannotListChangeOrDeleteLines() {
        UUID line = fixture.line(ids.timesheet("E1021"), D17, "Cutting", new BigDecimal("8"), "Draft");
        var timekeeper = flow.loggedInAs("timekeeper");

        assertThat(timekeeper.get(lines("E1021")).statusCode()).isEqualTo(403);
        assertThat(timekeeper.put("/api/v1/digital-timesheet-lines/" + line, toJson(Map.of("hours", 9))).statusCode()).isEqualTo(403);
        assertThat(timekeeper.delete("/api/v1/digital-timesheet-lines/" + line).statusCode()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT hours FROM bpa.digital_timesheet_line WHERE id = ?", BigDecimal.class, line)).isEqualByComparingTo("8");
    }

    @Test
    void aDraftLineInACutOffWeekIsClosedButAReturnedOneCanBeCorrected() {
        UUID draft = fixture.line(ids.timesheet("E1021"), LocalDate.of(2026, 11, 12), "Cutting", new BigDecimal("8"), "Draft");
        UUID returned = fixture.line(ids.timesheet("E1099"), LocalDate.of(2026, 11, 12), "Welding", new BigDecimal("8"), "Returned");
        var supervisor = flow.loggedInAs("supervisor");

        assertRefused(supervisor.put("/api/v1/digital-timesheet-lines/" + draft, toJson(Map.of("hours", 9))), 400, "Week ending 12 Nov 2026 is closed");
        assertRefused(supervisor.delete("/api/v1/digital-timesheet-lines/" + draft), 400, "Week ending 12 Nov 2026 is closed");
        HttpResponse<String> corrected = supervisor.put("/api/v1/digital-timesheet-lines/" + returned, toJson(Map.of("hours", 9)));
        assertThat(corrected.statusCode()).as(corrected.body()).isEqualTo(200);
        assertThat(json(corrected.body()).path("status").asText()).as("a Returned line stays Returned").isEqualTo("Returned");
    }

    @Test
    void aWorkerKeptForLoadedHoursTakesNoNewLines() {
        // E2-D16: HoursLoadedNotRemoved keeps the Worker's lines but blocks new ones
        jdbc.update("UPDATE bpa.worker_assignment SET flag = 'HoursLoadedNotRemoved' WHERE worker_id = (SELECT id FROM bpa.worker WHERE employee_ref = 'E1021')");

        assertRefused(add("supervisor", "E1021", "2026-11-17", "Cutting", "8", null), 400, "Worker E1021 is not assigned to WP-000457");
        assertThat(lineCount("E1021")).isZero();
    }

    @Test
    void insertAndDeleteAreAuditedColumnByColumn() {
        HttpResponse<String> saved = add("supervisor", "E1021", "2026-11-17", "Cutting", "8", "Night shift");
        UUID line = UUID.fromString(json(saved.body()).path("id").asText());

        assertThat(jdbc.queryForList("SELECT column_name || '=' || new_value FROM bpa.audit_log WHERE table_name = 'digital_timesheet_line' AND record_id = ? AND old_value IS NULL",
                String.class, line)).contains("hours=8", "phase=Cutting", "work_date=2026-11-17", "status=Draft", "entry_source=Form", "remarks=Night shift");

        assertThat(flow.loggedInAs("supervisor").delete("/api/v1/digital-timesheet-lines/" + line).statusCode()).isEqualTo(204);
        assertThat(jdbc.queryForList("SELECT column_name || ':' || old_value FROM bpa.audit_log WHERE table_name = 'digital_timesheet_line' AND record_id = ? AND new_value IS NULL AND old_value IS NOT NULL",
                String.class, line)).contains("hours:8", "phase:Cutting", "remarks:Night shift");
    }

    @Test
    void unknownTimesheetAndLineAnswer404() {
        UUID unknown = UUID.randomUUID();
        var supervisor = flow.loggedInAs("supervisor");

        assertRefused(supervisor.get("/api/v1/digital-timesheets/" + unknown + "/lines"), 404, "Time sheet not found");
        assertRefused(supervisor.post("/api/v1/digital-timesheets/" + unknown + "/lines", toJson(body("2026-11-17", "Cutting", "8", null))), 404, "Time sheet not found");
        assertRefused(supervisor.put("/api/v1/digital-timesheet-lines/" + unknown, toJson(Map.of("hours", 9))), 404, "Line not found");
        assertRefused(supervisor.delete("/api/v1/digital-timesheet-lines/" + unknown), 404, "Line not found");
    }

    @Test
    void aBodyWithoutAPhaseIsRefused() {
        HttpResponse<String> refused = flow.loggedInAs("supervisor").post(lines("E1021"), toJson(Map.of("workDate", "2026-11-17", "hours", 8)));

        assertRefused(refused, 400, "workDate, phase and hours are required");
        assertThat(lineCount("E1021")).isZero();
    }

    @Test
    void aChangeReplacesTheRemarkAndTheCompletionItDoesNotCarry() {
        // PUT replaces hours, remarks and reported completion (plan: ASSUMPTION): a body with hours only clears the other two, and audits it
        Map<String, Object> body = body("2026-11-17", "Cutting", "8", "Night shift");
        body.put("reportedCompletionPct", new BigDecimal("40"));
        UUID line = UUID.fromString(json(flow.loggedInAs("supervisor").post(lines("E1021"), toJson(body)).body()).path("id").asText());

        HttpResponse<String> changed = flow.loggedInAs("supervisor").put("/api/v1/digital-timesheet-lines/" + line, toJson(Map.of("hours", 9)));

        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(200);
        Map<String, Object> row = jdbc.queryForMap("SELECT remarks, reported_completion_pct FROM bpa.digital_timesheet_line WHERE id = ?", line);
        assertThat(row.get("remarks")).isNull();
        assertThat(row.get("reported_completion_pct")).isNull();
        assertThat(jdbc.queryForList("SELECT column_name || ':' || old_value || '>' || coalesce(new_value, 'null') FROM bpa.audit_log WHERE record_id = ? AND old_value IS NOT NULL AND new_value IS NULL AND column_name IN ('remarks', 'reported_completion_pct')",
                String.class, line)).containsExactlyInAnyOrder("remarks:Night shift>null", "reported_completion_pct:40>null");
    }

    // --- helpers ----------------------------------------------------------------------------------------------------------

    private String lines(String badge) {
        return "/api/v1/digital-timesheets/" + ids.timesheet(badge) + "/lines";
    }

    private static Map<String, Object> body(String date, String phase, String hours, String remarks) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workDate", date);
        body.put("phase", phase);
        body.put("hours", new BigDecimal(hours));
        if (remarks != null) {
            body.put("remarks", remarks);
        }
        return body;
    }

    private HttpResponse<String> add(String persona, String badge, String date, String phase, String hours, String remarks) {
        return flow.loggedInAs(persona).post(lines(badge), toJson(body(date, phase, hours, remarks)));
    }

    private static void assertRefused(HttpResponse<String> response, int status, String message) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(json(response.body()).path("detail").asText()).isEqualTo(message);
    }

    private int lineCount(String badge) {
        return jdbc.queryForObject("SELECT count(*) FROM bpa.digital_timesheet_line WHERE digital_timesheet_id = ?", Integer.class, ids.timesheet(badge));
    }

    private String workPackStatus() {
        return jdbc.queryForObject("SELECT status FROM bpa.workpack WHERE id = ?", String.class, ids.workpackId());
    }
}
