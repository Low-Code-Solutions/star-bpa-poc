package com.lcs.star.bpa;

import static com.lcs.star.bpa.CamundaSteps.json;
import static com.lcs.star.bpa.CamundaSteps.openTasks;
import static com.lcs.star.bpa.CamundaSteps.poll;
import static com.lcs.star.bpa.CamundaSteps.toJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lcs.star.bpa.CamundaRest.Reply;
import com.lcs.star.bpa.worker.RecordHoursDecisionWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
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
 * BPA-57 (E2-07): PPC gives the last approval. One test per Gherkin scenario, named as in the Jira Definition of Done. Today is 18 Nov 2026 (Asia/Riyadh).
 * Background: {@link BookHoursFixture} (WP-000457) with Draft lines E1021 16 Nov Cutting 8 h and E1099 16 Nov Cutting 7 h made by SQL; an instance of
 * Process_PPC_TimesheetToProgress started at UserTask_BookHours, sent by supervisor, validated by timekeeper and approved by production-supervisor through the
 * task API, so it waits at UserTask_ApproveHoursPpc. The workers run in the app.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(AssignmentFileApiTest.TestClockConfig.class)
class HoursPpcApprovalApiTest {

    static final Instant TODAY = Instant.parse("2026-11-18T06:00:00Z"); // 09:00 in Riyadh
    static final LocalDate D16 = LocalDate.of(2026, 11, 16);
    static final String PROCESS = "Process_PPC_TimesheetToProgress";
    static final String BOOK_HOURS = "UserTask_BookHours";
    static final String VALIDATE = "UserTask_ValidateHours";
    static final String APPROVE_PRODUCTION = "UserTask_ApproveHoursProduction";
    static final String APPROVE_PPC = "UserTask_ApproveHoursPpc";
    static final String EARLIER = "You already decided these hours at an earlier step";
    static final String NOT_EDITABLE = "Line is not editable (status Approved)";

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
    RecordHoursDecisionWorker worker;

    private BookHoursFixture fixture;
    private BookHoursFixture.Ids ids;
    private AssignmentFlow flow;

    /** All test classes share one Camunda: no task left waiting by another class may mix with this class's instances. */
    @BeforeAll
    static void noTaskLeftWaiting() {
        IntegrationApp.background();
        for (String element : new String[] {BOOK_HOURS, VALIDATE, APPROVE_PRODUCTION, APPROVE_PPC}) {
            CamundaSteps.cancelInstancesWaitingAt(element);
        }
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
    void BPA57_ppcApprovesHours() {
        long instance = productionApprovedHours();

        HttpResponse<String> done = decide("ppc-team", awaitTask(instance, APPROVE_PPC), "Approved", null);

        assertThat(done.statusCode()).as(done.body()).isEqualTo(204);
        awaitTask(instance, BOOK_HOURS);
        assertThat(lineStatuses(instance)).containsExactly("Approved");
        Map<String, Object> submission = jdbc.queryForMap("SELECT * FROM bpa.hours_submission WHERE process_instance_key = ?", instance);
        assertThat(submission.get("stage")).isEqualTo("Closed");
        assertThat(submission.get("outcome")).isEqualTo("Approved");
        Map<String, Object> decision = jdbc.queryForMap("SELECT * FROM bpa.hours_decision WHERE step = 'Ppc'");
        assertThat(decision.get("decision")).isEqualTo("Approved");
        assertThat(decision.get("decided_by")).isEqualTo("ppc-team");
    }

    @Test
    void BPA57_approvedLinesCannotChange() {
        long instance = productionApprovedHours();
        assertThat(decide("ppc-team", awaitTask(instance, APPROVE_PPC), "Approved", null).statusCode()).isEqualTo(204);
        awaitTask(instance, BOOK_HOURS);
        UUID line = e1021Line();

        HttpResponse<String> refused = flow.loggedInAs("supervisor").put("/api/v1/digital-timesheet-lines/" + line, toJson(Map.of("hours", new BigDecimal("9"))));

        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(json(refused.body()).path("detail").asText()).isEqualTo(NOT_EDITABLE);
        assertThat(jdbc.queryForObject("SELECT hours FROM bpa.digital_timesheet_line WHERE id = ?", BigDecimal.class, line)).isEqualByComparingTo("8");
    }

    @Test
    void BPA57_ppcReturnsHours() {
        long instance = productionApprovedHours();

        HttpResponse<String> done = decide("ppc-team", awaitTask(instance, APPROVE_PPC), "Returned", "Check phase");

        assertThat(done.statusCode()).as(done.body()).isEqualTo(204);
        String back = awaitTask(instance, BOOK_HOURS);
        assertThat(lineStatuses(instance)).containsExactly("Returned");
        assertThat(json(flow.loggedInAs("supervisor").get("/api/v1/tasks/" + back).body()).path("variables").path("hoursReason").asText())
                .as("Book hours shows the reason").isEqualTo("Check phase");
    }

    @Test
    void BPA57_ppcRejectsHours() {
        long instance = productionApprovedHours();

        HttpResponse<String> done = decide("ppc-team", awaitTask(instance, APPROVE_PPC), "Rejected", "Duplicate of WP-000461");

        assertThat(done.statusCode()).as(done.body()).isEqualTo(204);
        awaitTask(instance, BOOK_HOURS);
        assertThat(lineStatuses(instance)).containsExactly("Rejected");
        Map<String, Object> submission = jdbc.queryForMap("SELECT * FROM bpa.hours_submission WHERE process_instance_key = ?", instance);
        assertThat(submission.get("stage")).isEqualTo("Closed");
        assertThat(submission.get("outcome")).isEqualTo("Rejected");
    }

    @Test
    void BPA57_nextRoundAfterApproval() {
        long instance = productionApprovedHours();
        assertThat(decide("ppc-team", awaitTask(instance, APPROVE_PPC), "Approved", null).statusCode()).isEqualTo(204);

        String book = awaitTask(instance, BOOK_HOURS);

        List<String> groups = new ArrayList<>();
        openTasks(instance, BOOK_HOURS).get(0).path("candidateGroups").forEach(g -> groups.add(g.asText()));
        assertThat(groups).as("UserTask_BookHours for group supervisor").containsExactly("supervisor");
        assertThat(book).isNotBlank();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workDate", "2026-11-18");
        body.put("phase", "Cutting");
        body.put("hours", new BigDecimal("6"));
        HttpResponse<String> added = flow.loggedInAs("supervisor").post("/api/v1/digital-timesheets/" + ids.timesheet("E1021") + "/lines", toJson(body));
        assertThat(added.statusCode()).as(added.body()).isEqualTo(201);
    }

    @Test
    void BPA57_otherPersonasCannotApprovePpc() {
        long instance = productionApprovedHours();
        String task = awaitTask(instance, APPROVE_PPC);

        HttpResponse<String> refused = decide("production-supervisor", task, "Approved", null);

        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
        assertThat(json(refused.body()).path("detail").asText()).as("the candidate-group refusal").isEqualTo("You are not allowed to work on this task");
        assertThat(openTasks(instance, APPROVE_PPC)).as("the task stays open").hasSize(1);
    }

    @Test
    void BPA57_ppcDeciderMustDifferFromEarlierDeciders() {
        long instance = productionApprovedHours();
        String task = awaitTask(instance, APPROVE_PPC);
        // Given the production approval was recorded with decided_by ppc-team
        jdbc.update("UPDATE bpa.hours_decision SET decided_by = 'ppc-team' WHERE step = 'ProductionSupervisor'");

        HttpResponse<String> refused = decide("ppc-team", task, "Approved", null);

        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(json(refused.body()).path("detail").asText()).isEqualTo(EARLIER);
        assertThat(openTasks(instance, APPROVE_PPC)).as("the task stays open").hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.hours_decision WHERE step = 'Ppc'", Integer.class)).isZero();
    }

    // --- not scenarios ---------------------------------------------------------------------------------------------------

    @Test
    void aSecondRoundAfterApprovalIsRoundTwo() {
        long instance = productionApprovedHours();
        assertThat(decide("ppc-team", awaitTask(instance, APPROVE_PPC), "Approved", null).statusCode()).isEqualTo(204);
        String book = awaitTask(instance, BOOK_HOURS);
        fixture.line(ids.timesheet("E1021"), LocalDate.of(2026, 11, 17), "Cutting", new BigDecimal("5"), "Draft");

        assertThat(flow.loggedInAs("supervisor").post("/api/v1/tasks/" + book + "/complete", toJson(Map.of("variables", Map.of()))).statusCode()).isEqualTo(204);
        awaitTask(instance, VALIDATE);

        assertThat(jdbc.queryForObject("SELECT stage FROM bpa.hours_submission WHERE process_instance_key = ? AND round_no = 2", String.class, instance)).isEqualTo("Timekeeper");
        assertThat(jdbc.queryForObject("SELECT outcome FROM bpa.hours_submission WHERE process_instance_key = ? AND round_no = 1", String.class, instance)).isEqualTo("Approved");
    }

    @Test
    void theTimekeeperCannotAmendAnApprovedLine() {
        long instance = productionApprovedHours();
        assertThat(decide("ppc-team", awaitTask(instance, APPROVE_PPC), "Approved", null).statusCode()).isEqualTo(204);
        awaitTask(instance, BOOK_HOURS);

        HttpResponse<String> refused = flow.loggedInAs("timekeeper").post("/api/v1/digital-timesheet-lines/" + e1021Line() + "/amendments",
                toJson(Map.of("hours", new BigDecimal("9"), "reason", "Supervisor request")));

        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(json(refused.body()).path("detail").asText()).isEqualTo(NOT_EDITABLE);
    }

    @Test
    void anApprovedLineCannotBeDeleted() {
        long instance = productionApprovedHours();
        assertThat(decide("ppc-team", awaitTask(instance, APPROVE_PPC), "Approved", null).statusCode()).isEqualTo(204);
        awaitTask(instance, BOOK_HOURS);

        HttpResponse<String> refused = flow.loggedInAs("supervisor").delete("/api/v1/digital-timesheet-lines/" + e1021Line());

        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(json(refused.body()).path("detail").asText()).isEqualTo(NOT_EDITABLE);
    }

    @Test
    void theSenderIsRefusedAtPpcWithBpa56sText() {
        long instance = productionApprovedHours();
        String task = awaitTask(instance, APPROVE_PPC);
        jdbc.update("UPDATE bpa.hours_submission SET submitted_by = 'ppc-team' WHERE process_instance_key = ?", instance);

        HttpResponse<String> refused = decide("ppc-team", task, "Approved", null);

        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
        assertThat(json(refused.body()).path("detail").asText()).isEqualTo("Segregation of duties: ppc-team sent these hours and cannot decide on them");
        assertThat(openTasks(instance, APPROVE_PPC)).hasSize(1);
    }

    @Test
    void anEarlierTimekeeperDeciderIsRefusedAtPpc() {
        long instance = productionApprovedHours();
        String task = awaitTask(instance, APPROVE_PPC);
        jdbc.update("UPDATE bpa.hours_decision SET decided_by = 'ppc-team' WHERE step = 'Timekeeper'");

        HttpResponse<String> refused = decide("ppc-team", task, "Approved", null);

        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(json(refused.body()).path("detail").asText()).isEqualTo(EARLIER);
    }

    @Test
    void aRetriedPpcJobRecordsOneDecision() {
        long instance = productionApprovedHours();
        String task = awaitTask(instance, APPROVE_PPC);
        assertThat(decide("ppc-team", task, "Approved", null).statusCode()).isEqualTo(204);
        awaitTask(instance, BOOK_HOURS);
        long auditBefore = jdbc.queryForObject("SELECT count(*) FROM bpa.audit_log", Long.class);

        JobClient client = mock(JobClient.class, RETURNS_DEEP_STUBS);
        worker.handle(client, job(instance, Long.parseLong(task), "ppc-team", "Approved", null));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.hours_decision WHERE task_key = ?", Integer.class, Long.parseLong(task))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.audit_log", Long.class)).isEqualTo(auditBefore);
        verify(client).newCompleteCommand(anyLong());
    }

    @Test
    void thePpcJobFailsForAnEarlierDecider() {
        long instance = productionApprovedHours();
        String task = awaitTask(instance, APPROVE_PPC);
        jdbc.update("UPDATE bpa.hours_decision SET decided_by = 'ppc-team' WHERE step = 'ProductionSupervisor'");

        JobClient client = mock(JobClient.class, RETURNS_DEEP_STUBS);
        worker.handle(client, job(instance, Long.parseLong(task), "ppc-team", "Approved", null));

        verify(client.newFailCommand(anyLong()).retries(0)).errorMessage(EARLIER);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.hours_decision WHERE step = 'Ppc'", Integer.class)).isZero();
    }

    @Test
    void thePpcTeamReadsTheSubmission() {
        long instance = productionApprovedHours();
        UUID submission = jdbc.queryForObject("SELECT id FROM bpa.hours_submission WHERE process_instance_key = ?", UUID.class, instance);

        HttpResponse<String> read = flow.loggedInAs("ppc-team").get("/api/v1/hours-submissions/" + submission);

        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        assertThat(json(read.body()).path("stage").asText()).isEqualTo("Ppc");
    }

    // --- helpers ----------------------------------------------------------------------------------------------------------

    /** The Background: two Draft lines, sent, validated and production-approved; the instance waits at UserTask_ApproveHoursPpc. */
    private long productionApprovedHours() {
        fixture.line(ids.timesheet("E1021"), D16, "Cutting", new BigDecimal("8"), "Draft");
        fixture.line(ids.timesheet("E1099"), D16, "Cutting", new BigDecimal("7"), "Draft");
        long instance = startAtBookHours();
        String book = CamundaSteps.awaitOpenTask(instance, BOOK_HOURS).path("userTaskKey").asText();
        HttpResponse<String> sent = flow.loggedInAs("supervisor").post("/api/v1/tasks/" + book + "/complete", toJson(Map.of("variables", Map.of())));
        assertThat(sent.statusCode()).as("send: " + sent.body()).isEqualTo(204);
        HttpResponse<String> validated = decide("timekeeper", awaitTask(instance, VALIDATE), "Validated", null);
        assertThat(validated.statusCode()).as("validate: " + validated.body()).isEqualTo(204);
        HttpResponse<String> approved = decide("production-supervisor", awaitTask(instance, APPROVE_PRODUCTION), "Approved", null);
        assertThat(approved.statusCode()).as("production approve: " + approved.body()).isEqualTo(204);
        awaitTask(instance, APPROVE_PPC);
        return instance;
    }

    /** A record-hours-decision job of step Ppc as the engine would hand it over. */
    private ActivatedJob job(long instance, long taskKey, String completedBy, String decision, String reason) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("hoursSubmissionId", jdbc.queryForObject("SELECT id FROM bpa.hours_submission WHERE process_instance_key = ? AND round_no = 1",
                UUID.class, instance).toString());
        variables.put("hoursDecision", decision);
        variables.put("hoursReason", reason);
        variables.put("completedBy", completedBy);
        variables.put("completedTaskKey", Long.toString(taskKey));
        ActivatedJob job = mock(ActivatedJob.class);
        when(job.getKey()).thenReturn(4_000_000_002L);
        when(job.getProcessInstanceKey()).thenReturn(instance);
        when(job.getElementId()).thenReturn("ServiceTask_RecordPpcDecision");
        when(job.getVariablesAsMap()).thenReturn(variables);
        when(job.getCustomHeaders()).thenReturn(Map.of("step", "Ppc"));
        return job;
    }

    private UUID e1021Line() {
        return jdbc.queryForObject("SELECT id FROM bpa.digital_timesheet_line WHERE digital_timesheet_id = ? AND work_date = ?", UUID.class,
                ids.timesheet("E1021"), java.sql.Date.valueOf(D16));
    }

    private List<String> lineStatuses(long instance) {
        return jdbc.queryForList("""
                SELECT DISTINCT l.status FROM bpa.digital_timesheet_line l JOIN bpa.hours_submission s ON s.id = l.hours_submission_id
                WHERE s.process_instance_key = ?""", String.class, instance);
    }

    private String awaitTask(long instance, String elementId) {
        return poll(() -> openTasks(instance, elementId).stream().findFirst(), found -> found.isPresent(), elementId + " of " + instance).orElseThrow()
                .path("userTaskKey").asText();
    }

    private HttpResponse<String> decide(String user, String task, String decision, String reason) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("hoursDecision", decision);
        if (reason != null) {
            variables.put("hoursReason", reason);
        }
        return flow.loggedInAs(user).post("/api/v1/tasks/" + task + "/complete", toJson(Map.of("variables", variables)));
    }

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
