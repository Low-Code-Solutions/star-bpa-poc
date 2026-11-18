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
 * BPA-53 (E2-05): the Supervisor sends the hours, the Timekeeper validates or returns them, amends on request, and new lines wait while the hours are in
 * approval. One test per Gherkin scenario, named as in the Jira Definition of Done. Today is 18 Nov 2026 (Asia/Riyadh). The Background is BPA-50's
 * ({@link BookHoursFixture}: WP-000457, E1021 and E1099) with Draft lines E1021 16 Nov Cutting 8, E1021 17 Nov Cutting 8 and E1099 16 Nov Cutting 7 made by
 * SQL, and an instance of Process_PPC_TimesheetToProgress started at UserTask_BookHours, as BPA-49's message would have. The workers run in the app; the
 * tasks are completed through the task API.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(AssignmentFileApiTest.TestClockConfig.class)
class HoursValidationApiTest {

    static final Instant TODAY = Instant.parse("2026-11-18T06:00:00Z"); // 09:00 in Riyadh
    static final LocalDate D16 = LocalDate.of(2026, 11, 16);
    static final LocalDate D17 = LocalDate.of(2026, 11, 17);
    static final String PROCESS = "Process_PPC_TimesheetToProgress";
    static final String BOOK_HOURS = "UserTask_BookHours";
    static final String VALIDATE = "UserTask_ValidateHours";
    static final String APPROVE_PRODUCTION = "UserTask_ApproveHoursProduction";

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

    /** All test classes share one Camunda: no task left waiting by another class may mix with this class's instances. */
    @BeforeAll
    static void noTaskLeftWaiting() {
        IntegrationApp.background();
        CamundaSteps.cancelInstancesWaitingAt(BOOK_HOURS);
        CamundaSteps.cancelInstancesWaitingAt(VALIDATE);
        CamundaSteps.cancelInstancesWaitingAt(APPROVE_PRODUCTION);
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
    void BPA53_supervisorSendsHoursToTimekeeper() {
        draftLines();
        long instance = startAtBookHours();

        sendAsSupervisor(instance);

        assertThat(awaitTask(instance, VALIDATE)).isNotBlank();
        assertThat(jdbc.queryForList("SELECT status FROM bpa.digital_timesheet_line", String.class)).containsExactly("Submitted", "Submitted", "Submitted");
        Map<String, Object> submission = jdbc.queryForMap("SELECT * FROM bpa.hours_submission WHERE process_instance_key = ?", instance);
        assertThat(submission.get("stage")).isEqualTo("Timekeeper");
        assertThat(submission.get("submitted_by")).isEqualTo("supervisor");
        assertThat(submission.get("round_no")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.digital_timesheet_line WHERE hours_submission_id = ?", Integer.class, submission.get("id"))).isEqualTo(3);
    }

    @Test
    void BPA53_nothingToSendStaysWithSupervisor() {
        // every line is already Submitted: nothing is Draft or Returned
        fixture.line(ids.timesheet("E1021"), D16, "Cutting", new BigDecimal("8"), "Submitted");
        long instance = startAtBookHours();
        String task = CamundaSteps.awaitOpenTask(instance, BOOK_HOURS).path("userTaskKey").asText();

        assertThat(flow.loggedInAs("supervisor").post("/api/v1/tasks/" + task + "/complete", toJson(Map.of("variables", Map.of()))).statusCode()).isEqualTo(204);

        String again = poll(() -> openTasks(instance, BOOK_HOURS).stream().filter(t -> !task.equals(t.path("userTaskKey").asText())).findFirst(),
                found -> found.isPresent(), "UserTask_BookHours of " + instance + " again").orElseThrow().path("userTaskKey").asText();
        List<String> messages = new ArrayList<>();
        json(flow.loggedInAs("supervisor").get("/api/v1/tasks/" + again).body()).path("validationMessages").forEach(m -> messages.add(m.asText()));
        assertThat(messages).containsExactly("No Draft or Returned hours to send for WP-000457");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.hours_submission", Integer.class)).isZero();
    }

    @Test
    void BPA53_timekeeperValidates() {
        draftLines();
        long instance = startAtBookHours();
        sendAsSupervisor(instance);

        HttpResponse<String> done = decide(awaitTask(instance, VALIDATE), "Validated", null);

        assertThat(done.statusCode()).as(done.body()).isEqualTo(204);
        awaitTask(instance, APPROVE_PRODUCTION);
        Map<String, Object> submission = jdbc.queryForMap("SELECT * FROM bpa.hours_submission WHERE process_instance_key = ?", instance);
        assertThat(submission.get("stage")).isEqualTo("ProductionSupervisor");
        Map<String, Object> decision = jdbc.queryForMap("SELECT * FROM bpa.hours_decision WHERE hours_submission_id = ?", submission.get("id"));
        assertThat(decision.get("step")).isEqualTo("Timekeeper");
        assertThat(decision.get("decision")).isEqualTo("Validated");
        assertThat(decision.get("decided_by")).isEqualTo("timekeeper");
        assertThat(jdbc.queryForList("SELECT DISTINCT status FROM bpa.digital_timesheet_line", String.class)).containsExactly("Submitted");
    }

    @Test
    void BPA53_timekeeperReturnsWithReason() {
        draftLines();
        long instance = startAtBookHours();
        sendAsSupervisor(instance);

        HttpResponse<String> done = decide(awaitTask(instance, VALIDATE), "Returned", "E1099 16 Nov should be Welding");

        assertThat(done.statusCode()).as(done.body()).isEqualTo(204);
        String back = awaitTask(instance, BOOK_HOURS);
        assertThat(jdbc.queryForList("SELECT DISTINCT status FROM bpa.digital_timesheet_line", String.class)).containsExactly("Returned");
        Map<String, Object> submission = jdbc.queryForMap("SELECT * FROM bpa.hours_submission WHERE process_instance_key = ?", instance);
        assertThat(submission.get("stage")).isEqualTo("Closed");
        assertThat(submission.get("outcome")).isEqualTo("Returned");
        Map<String, Object> decision = jdbc.queryForMap("SELECT * FROM bpa.hours_decision WHERE hours_submission_id = ?", submission.get("id"));
        assertThat(decision.get("reason")).isEqualTo("E1099 16 Nov should be Welding");
        JsonNode task = json(flow.loggedInAs("supervisor").get("/api/v1/tasks/" + back).body());
        assertThat(task.path("variables").path("hoursReason").asText()).as("the Supervisor sees the reason").isEqualTo("E1099 16 Nov should be Welding");
    }

    @Test
    void BPA53_returnNeedsAReason() {
        draftLines();
        long instance = startAtBookHours();
        sendAsSupervisor(instance);
        String task = awaitTask(instance, VALIDATE);

        HttpResponse<String> refused = decide(task, "Returned", "");

        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(400);
        assertThat(json(refused.body()).path("detail").asText()).isEqualTo("Reason is required");
        assertThat(openTasks(instance, VALIDATE)).as("the task stays open").hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.hours_decision", Integer.class)).isZero();
    }

    @Test
    void BPA53_returnedLinesCanBeCorrectedAndResent() {
        draftLines();
        long instance = startAtBookHours();
        sendAsSupervisor(instance);
        assertThat(decide(awaitTask(instance, VALIDATE), "Returned", "E1099 16 Nov should be Welding").statusCode()).isEqualTo(204);
        String back = awaitTask(instance, BOOK_HOURS);

        // the Supervisor corrects it: deletes E1099's Returned Cutting line (plan Q6) and books Welding instead
        HttpResponse<String> deleted = flow.loggedInAs("supervisor").delete("/api/v1/digital-timesheet-lines/" + e1099Line());
        assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(204);
        Map<String, Object> welding = new LinkedHashMap<>();
        welding.put("workDate", "2026-11-16");
        welding.put("phase", "Welding");
        welding.put("hours", new BigDecimal("7"));
        HttpResponse<String> added = flow.loggedInAs("supervisor").post("/api/v1/digital-timesheets/" + ids.timesheet("E1099") + "/lines", toJson(welding));
        assertThat(added.statusCode()).as(added.body()).isEqualTo(201);

        assertThat(flow.loggedInAs("supervisor").post("/api/v1/tasks/" + back + "/complete", toJson(Map.of("variables", Map.of()))).statusCode()).isEqualTo(204);

        poll(() -> jdbc.queryForObject("SELECT count(*) FROM bpa.hours_submission WHERE process_instance_key = ?", Integer.class, instance),
                n -> n == 2, "a second submission of " + instance);
        Map<String, Object> second = jdbc.queryForMap("SELECT * FROM bpa.hours_submission WHERE process_instance_key = ? AND round_no = 2", instance);
        assertThat(second.get("stage")).isEqualTo("Timekeeper");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.digital_timesheet_line WHERE hours_submission_id = ? AND status = 'Submitted'", Integer.class,
                second.get("id"))).isEqualTo(3);
    }

    @Test
    void BPA53_linesBookedAfterSendingWaitForNextRound() {
        draftLines();
        long instance = startAtBookHours();
        sendAsSupervisor(instance);
        awaitTask(instance, VALIDATE);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workDate", "2026-11-18");
        body.put("phase", "Cutting");
        body.put("hours", new BigDecimal("6"));
        HttpResponse<String> refused = flow.loggedInAs("supervisor").post("/api/v1/digital-timesheets/" + ids.timesheet("E1021") + "/lines", toJson(body));

        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(json(refused.body()).path("detail").asText()).isEqualTo("Hours for WP-000457 are in approval; book them after the decision");
    }

    @Test
    void BPA53_otherPersonasCannotValidate() {
        draftLines();
        long instance = startAtBookHours();
        sendAsSupervisor(instance);
        String task = awaitTask(instance, VALIDATE);

        HttpResponse<String> refused = flow.loggedInAs("production-supervisor").post("/api/v1/tasks/" + task + "/complete",
                toJson(Map.of("variables", Map.of("hoursDecision", "Validated"))));

        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
        assertThat(openTasks(instance, VALIDATE)).hasSize(1);
    }

    @Test
    void BPA53_timekeeperAmendsHoursOnRequest() {
        draftLines();
        long instance = startAtBookHours();
        sendAsSupervisor(instance);
        awaitTask(instance, VALIDATE);
        UUID line = e1099Line();

        HttpResponse<String> amended = amend(line, "7.5", "Supervisor request");

        assertThat(amended.statusCode()).as(amended.body()).isEqualTo(200);
        assertThat(json(amended.body()).path("hours").decimalValue()).isEqualByComparingTo("7.5");
        assertThat(jdbc.queryForObject("SELECT status FROM bpa.digital_timesheet_line WHERE id = ?", String.class, line)).isEqualTo("Submitted");
        List<Map<String, Object>> audit = jdbc.queryForList("""
                SELECT old_value, new_value, username FROM bpa.audit_log
                WHERE table_name = 'digital_timesheet_line' AND record_id = ? AND column_name = 'hours' AND old_value IS NOT NULL""", line);
        assertThat(audit).hasSize(1);
        assertThat(new BigDecimal(audit.get(0).get("old_value").toString())).isEqualByComparingTo("7");
        assertThat(new BigDecimal(audit.get(0).get("new_value").toString())).isEqualByComparingTo("7.5");
        assertThat(audit.get(0).get("username")).isEqualTo("timekeeper");
    }

    @Test
    void BPA53_amendmentAboveBudgetIsRefused() {
        // Cutting stands at 299.5 h in all: the three sent lines (23 h) and 276.5 h already Approved
        draftLines();
        fixture.fill(ids.timesheet("E1099"), "Cutting", new BigDecimal("276.5"), "Approved");
        long instance = startAtBookHours();
        sendAsSupervisor(instance);
        awaitTask(instance, VALIDATE);
        UUID line = e1099Line();

        HttpResponse<String> refused = amend(line, "8", "Supervisor request");

        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(400);
        assertThat(json(refused.body()).path("detail").asText()).isEqualTo("Phase Cutting budget of 300 h would be exceeded");
        assertThat(jdbc.queryForObject("SELECT hours FROM bpa.digital_timesheet_line WHERE id = ?", BigDecimal.class, line)).isEqualByComparingTo("7");
    }

    // --- not scenarios: a retried job changes nothing (ADR 0007, D65) ---------------------------------------------------------

    @Autowired
    com.lcs.star.bpa.persistence.HoursSubmissionRepository submissions;

    @Test
    void aRetriedSendCreatesNoSecondSubmissionAndNoSecondAudit() {
        draftLines();
        var first = submissions.send(9100000001L, ids.workpackId(), "supervisor");
        long auditAfterFirst = jdbc.queryForObject("SELECT count(*) FROM bpa.audit_log", Long.class);

        var again = submissions.send(9100000001L, ids.workpackId(), "supervisor");

        assertThat(again.submissionId()).isEqualTo(first.submissionId());
        assertThat(first.lineCount()).isEqualTo(3);
        assertThat(again.lineCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.hours_submission", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.audit_log", Long.class)).isEqualTo(auditAfterFirst);
    }

    @Test
    void aRetriedDecisionWritesNothingMore() {
        draftLines();
        var sent = submissions.send(9100000002L, ids.workpackId(), "supervisor");

        var first = submissions.decideAsTimekeeper(sent.submissionId(), 777L, false, "Wrong phase", "timekeeper");
        long auditAfterFirst = jdbc.queryForObject("SELECT count(*) FROM bpa.audit_log", Long.class);
        var again = submissions.decideAsTimekeeper(sent.submissionId(), 777L, false, "Wrong phase", "timekeeper");

        assertThat(first).isEqualTo(com.lcs.star.bpa.persistence.HoursSubmissionRepository.Outcome.RETURNED);
        assertThat(again).isEqualTo(com.lcs.star.bpa.persistence.HoursSubmissionRepository.Outcome.ALREADY_RECORDED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.hours_decision", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.audit_log", Long.class)).isEqualTo(auditAfterFirst);
    }

    @Test
    void theSubmissionIsReadByTheTimekeeperOnly() {
        draftLines();
        var sent = submissions.send(9100000003L, ids.workpackId(), "supervisor");

        HttpResponse<String> read = flow.loggedInAs("timekeeper").get("/api/v1/hours-submissions/" + sent.submissionId());
        HttpResponse<String> refused = flow.loggedInAs("supervisor").get("/api/v1/hours-submissions/" + sent.submissionId());
        HttpResponse<String> missing = flow.loggedInAs("timekeeper").get("/api/v1/hours-submissions/" + UUID.randomUUID());

        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        assertThat(json(read.body()).path("workpackNumber").asText()).isEqualTo("WP-000457");
        assertThat(json(read.body()).path("stage").asText()).isEqualTo("Timekeeper");
        assertThat(json(read.body()).path("lines")).hasSize(3);
        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(missing.statusCode()).isEqualTo(404);
    }

    // --- helpers ----------------------------------------------------------------------------------------------------------

    /** The Background's Draft lines: E1021 16 and 17 Nov Cutting 8 h, E1099 16 Nov Cutting 7 h. */
    private void draftLines() {
        fixture.line(ids.timesheet("E1021"), D16, "Cutting", new BigDecimal("8"), "Draft");
        fixture.line(ids.timesheet("E1021"), D17, "Cutting", new BigDecimal("8"), "Draft");
        fixture.line(ids.timesheet("E1099"), D16, "Cutting", new BigDecimal("7"), "Draft");
    }

    private UUID e1099Line() {
        return jdbc.queryForObject("SELECT id FROM bpa.digital_timesheet_line WHERE digital_timesheet_id = ? AND work_date = ? AND phase = 'Cutting'", UUID.class,
                ids.timesheet("E1099"), java.sql.Date.valueOf(D16));
    }

    /** The Supervisor completes UserTask_BookHours: "Send to Timekeeper". */
    private void sendAsSupervisor(long instance) {
        String task = CamundaSteps.awaitOpenTask(instance, BOOK_HOURS).path("userTaskKey").asText();
        HttpResponse<String> sent = flow.loggedInAs("supervisor").post("/api/v1/tasks/" + task + "/complete", toJson(Map.of("variables", Map.of())));
        assertThat(sent.statusCode()).as("send: " + sent.body()).isEqualTo(204);
    }

    /** The key of the open task at the element, once it exists. */
    private String awaitTask(long instance, String elementId) {
        return poll(() -> openTasks(instance, elementId).stream().findFirst(), found -> found.isPresent(), elementId + " of " + instance).orElseThrow()
                .path("userTaskKey").asText();
    }

    private HttpResponse<String> decide(String task, String decision, String reason) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("hoursDecision", decision);
        if (reason != null) {
            variables.put("hoursReason", reason);
        }
        return flow.loggedInAs("timekeeper").post("/api/v1/tasks/" + task + "/complete", toJson(Map.of("variables", variables)));
    }

    private HttpResponse<String> amend(UUID line, String hours, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("hours", new BigDecimal(hours));
        body.put("reason", reason);
        return flow.loggedInAs("timekeeper").post("/api/v1/digital-timesheet-lines/" + line + "/amendments", toJson(body));
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
