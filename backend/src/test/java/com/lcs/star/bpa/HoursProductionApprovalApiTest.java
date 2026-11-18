package com.lcs.star.bpa;

import static com.lcs.star.bpa.CamundaSteps.json;
import static com.lcs.star.bpa.CamundaSteps.openTasks;
import static com.lcs.star.bpa.CamundaSteps.poll;
import static com.lcs.star.bpa.CamundaSteps.toJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
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
 * BPA-56 (E2-06): the Production Supervisor approves, returns or rejects the hours the Timekeeper validated. One test per Gherkin scenario, named as in the
 * Jira Definition of Done; the Scenario Outline's two rows run inside one test. Today is 18 Nov 2026 (Asia/Riyadh). Background: {@link BookHoursFixture}
 * (WP-000457) with Draft lines E1021 16 Nov Cutting 8 h and E1099 16 Nov Cutting 7 h made by SQL; an instance of Process_PPC_TimesheetToProgress started at
 * UserTask_BookHours, sent by supervisor and validated by timekeeper through the task API, so it waits at UserTask_ApproveHoursProduction. The workers run in
 * the app.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(AssignmentFileApiTest.TestClockConfig.class)
class HoursProductionApprovalApiTest {

    static final Instant TODAY = Instant.parse("2026-11-18T06:00:00Z"); // 09:00 in Riyadh
    static final LocalDate D16 = LocalDate.of(2026, 11, 16);
    static final String PROCESS = "Process_PPC_TimesheetToProgress";
    static final String BOOK_HOURS = "UserTask_BookHours";
    static final String VALIDATE = "UserTask_ValidateHours";
    static final String APPROVE_PRODUCTION = "UserTask_ApproveHoursProduction";
    static final String APPROVE_PPC = "UserTask_ApproveHoursPpc";
    static final String SEGREGATION = "Segregation of duties: production-supervisor sent these hours and cannot decide on them";

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
    void BPA56_productionSupervisorApproves() {
        long instance = validatedHours();

        HttpResponse<String> done = decide("production-supervisor", awaitTask(instance, APPROVE_PRODUCTION), "Approved", null);

        assertThat(done.statusCode()).as(done.body()).isEqualTo(204);
        awaitTask(instance, APPROVE_PPC);
        List<String> groups = new java.util.ArrayList<>();
        openTasks(instance, APPROVE_PPC).get(0).path("candidateGroups").forEach(g -> groups.add(g.asText()));
        assertThat(groups).as("a UserTask_ApproveHoursPpc task for group ppc-team").containsExactly("ppc-team");
        assertThat(stage(instance, 1)).isEqualTo("Ppc");
        assertThat(lineStatuses(instance)).containsExactly("Submitted");
        Map<String, Object> decision = jdbc.queryForMap("SELECT * FROM bpa.hours_decision WHERE step = 'ProductionSupervisor'");
        assertThat(decision.get("decision")).isEqualTo("Approved");
        assertThat(decision.get("decided_by")).isEqualTo("production-supervisor");
    }

    @Test
    void BPA56_productionSupervisorReturns() {
        long instance = validatedHours();

        HttpResponse<String> done = decide("production-supervisor", awaitTask(instance, APPROVE_PRODUCTION), "Returned", "E1099 was on Welding");

        assertThat(done.statusCode()).as(done.body()).isEqualTo(204);
        String back = awaitTask(instance, BOOK_HOURS);
        assertThat(lineStatuses(instance)).containsExactly("Returned");
        Map<String, Object> submission = jdbc.queryForMap("SELECT * FROM bpa.hours_submission WHERE process_instance_key = ?", instance);
        assertThat(submission.get("stage")).isEqualTo("Closed");
        assertThat(submission.get("outcome")).isEqualTo("Returned");
        assertThat(json(flow.loggedInAs("supervisor").get("/api/v1/tasks/" + back).body()).path("variables").path("hoursReason").asText())
                .as("Book hours shows the reason").isEqualTo("E1099 was on Welding");
    }

    @Test
    void BPA56_productionSupervisorRejects() {
        long instance = validatedHours();

        HttpResponse<String> done = decide("production-supervisor", awaitTask(instance, APPROVE_PRODUCTION), "Rejected", "Hours not worked on this Work Pack");

        assertThat(done.statusCode()).as(done.body()).isEqualTo(204);
        awaitTask(instance, BOOK_HOURS);
        assertThat(lineStatuses(instance)).containsExactly("Rejected");
        Map<String, Object> submission = jdbc.queryForMap("SELECT * FROM bpa.hours_submission WHERE process_instance_key = ?", instance);
        assertThat(submission.get("stage")).isEqualTo("Closed");
        assertThat(submission.get("outcome")).isEqualTo("Rejected");
    }

    @Test
    void BPA56_returnOrRejectNeedsAReason() {
        long instance = validatedHours();
        String task = awaitTask(instance, APPROVE_PRODUCTION);

        for (String decision : List.of("Returned", "Rejected")) {
            HttpResponse<String> refused = decide("production-supervisor", task, decision, "");

            assertThat(refused.statusCode()).as(decision + ": " + refused.body()).isEqualTo(400);
            assertThat(json(refused.body()).path("detail").asText()).as(decision).isEqualTo("Reason is required");
        }
        assertThat(openTasks(instance, APPROVE_PRODUCTION)).as("the task stays open").hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.hours_decision WHERE step = 'ProductionSupervisor'", Integer.class)).isZero();
    }

    @Test
    void BPA56_rerunRecordsOneDecision() {
        long instance = validatedHours();
        String task = awaitTask(instance, APPROVE_PRODUCTION);
        assertThat(decide("production-supervisor", task, "Approved", null).statusCode()).isEqualTo(204);
        awaitTask(instance, APPROVE_PPC);
        long auditBefore = jdbc.queryForObject("SELECT count(*) FROM bpa.audit_log", Long.class);

        // the job is retried: the worker runs again with the same variables and the same task key (new-job-worker skill)
        JobClient client = mock(JobClient.class, RETURNS_DEEP_STUBS);
        worker.handle(client, job(instance, Long.parseLong(task), "production-supervisor", "Approved", null));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.hours_decision WHERE task_key = ?", Integer.class, Long.parseLong(task))).isEqualTo(1);
        assertThat(stage(instance, 1)).isEqualTo("Ppc");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.audit_log", Long.class)).isEqualTo(auditBefore);
        verify(client).newCompleteCommand(anyLong());
    }

    @Test
    void BPA56_otherPersonasCannotApproveProduction() {
        long instance = validatedHours();
        String task = awaitTask(instance, APPROVE_PRODUCTION);

        HttpResponse<String> refused = decide("timekeeper", task, "Approved", null);

        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
        assertThat(json(refused.body()).path("detail").asText()).as("the candidate-group refusal, not CSRF or segregation").isEqualTo("You are not allowed to work on this task");
        assertThat(openTasks(instance, APPROVE_PRODUCTION)).hasSize(1);
    }

    // --- not scenarios ---------------------------------------------------------------------------------------------------

    @Test
    void aRejectedLineCanBeBookedAgainAndIsNotResent() {
        long instance = validatedHours();
        decide("production-supervisor", awaitTask(instance, APPROVE_PRODUCTION), "Rejected", "Hours not worked on this Work Pack");
        String back = awaitTask(instance, BOOK_HOURS);
        UUID rejected = jdbc.queryForObject("SELECT id FROM bpa.digital_timesheet_line WHERE digital_timesheet_id = ?", UUID.class, ids.timesheet("E1099"));

        // the same Worker, date and phase again: the partial unique key ignores the Rejected line (BPA-48)
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workDate", "2026-11-16");
        body.put("phase", "Cutting");
        body.put("hours", new BigDecimal("6"));
        HttpResponse<String> rebooked = flow.loggedInAs("supervisor").post("/api/v1/digital-timesheets/" + ids.timesheet("E1099") + "/lines", toJson(body));
        assertThat(rebooked.statusCode()).as(rebooked.body()).isEqualTo(201);

        assertThat(flow.loggedInAs("supervisor").post("/api/v1/tasks/" + back + "/complete", toJson(Map.of("variables", Map.of()))).statusCode()).isEqualTo(204);
        awaitTask(instance, VALIDATE);

        UUID round2 = jdbc.queryForObject("SELECT id FROM bpa.hours_submission WHERE process_instance_key = ? AND round_no = 2", UUID.class, instance);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.digital_timesheet_line WHERE hours_submission_id = ?", Integer.class, round2))
                .as("round 2 holds only the new line").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM bpa.digital_timesheet_line WHERE id = ?", String.class, rejected)).isEqualTo("Rejected");
        assertThat(jdbc.queryForObject("SELECT h.round_no FROM bpa.digital_timesheet_line l JOIN bpa.hours_submission h ON h.id = l.hours_submission_id WHERE l.id = ?",
                Integer.class, rejected)).as("the Rejected line stays with round 1").isEqualTo(1);
    }

    @Test
    void segregationRefusesTheSenderAtTheTaskApi() {
        long instance = validatedHours();
        String task = awaitTask(instance, APPROVE_PRODUCTION);
        jdbc.update("UPDATE bpa.hours_submission SET submitted_by = 'production-supervisor' WHERE process_instance_key = ?", instance);

        HttpResponse<String> refused = decide("production-supervisor", task, "Approved", null);

        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
        assertThat(json(refused.body()).path("detail").asText()).isEqualTo(SEGREGATION);
        assertThat(openTasks(instance, APPROVE_PRODUCTION)).as("the task stays open").hasSize(1);
    }

    @Test
    void segregationAlsoFailsTheJob() {
        long instance = validatedHours();
        String task = awaitTask(instance, APPROVE_PRODUCTION);
        jdbc.update("UPDATE bpa.hours_submission SET submitted_by = 'production-supervisor' WHERE process_instance_key = ?", instance);

        JobClient client = mock(JobClient.class, RETURNS_DEEP_STUBS);
        worker.handle(client, job(instance, Long.parseLong(task), "production-supervisor", "Approved", null));

        verify(client.newFailCommand(anyLong()).retries(0)).errorMessage(SEGREGATION);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.hours_decision WHERE step = 'ProductionSupervisor'", Integer.class)).isZero();
    }

    @Test
    void aDecisionForASubmissionAtAnotherStageFailsTheJob() {
        long instance = validatedHours();
        String task = awaitTask(instance, APPROVE_PRODUCTION);
        jdbc.update("UPDATE bpa.hours_submission SET stage = 'Timekeeper' WHERE process_instance_key = ?", instance);

        JobClient client = mock(JobClient.class, RETURNS_DEEP_STUBS);
        worker.handle(client, job(instance, Long.parseLong(task), "production-supervisor", "Approved", null));

        verify(client.newFailCommand(anyLong()).retries(0)).errorMessage(contains("is not at stage ProductionSupervisor"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.hours_decision WHERE step = 'ProductionSupervisor'", Integer.class)).isZero();
        assertThat(stage(instance, 1)).isEqualTo("Timekeeper");
    }

    @Test
    void theProductionSupervisorReadsTheSubmissionButCannotAmend() {
        long instance = validatedHours();
        UUID submission = jdbc.queryForObject("SELECT id FROM bpa.hours_submission WHERE process_instance_key = ?", UUID.class, instance);
        UUID line = jdbc.queryForObject("SELECT id FROM bpa.digital_timesheet_line WHERE digital_timesheet_id = ?", UUID.class, ids.timesheet("E1099"));

        HttpResponse<String> read = flow.loggedInAs("production-supervisor").get("/api/v1/hours-submissions/" + submission);
        HttpResponse<String> amend = flow.loggedInAs("production-supervisor").post("/api/v1/digital-timesheet-lines/" + line + "/amendments",
                toJson(Map.of("hours", new BigDecimal("6"), "reason", "Supervisor request")));

        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        assertThat(json(read.body()).path("stage").asText()).isEqualTo("ProductionSupervisor");
        assertThat(amend.statusCode()).isEqualTo(403);
    }

    // --- helpers ----------------------------------------------------------------------------------------------------------

    /** The Background: two Draft lines, sent by supervisor and validated by timekeeper; the instance waits at UserTask_ApproveHoursProduction. */
    private long validatedHours() {
        fixture.line(ids.timesheet("E1021"), D16, "Cutting", new BigDecimal("8"), "Draft");
        fixture.line(ids.timesheet("E1099"), D16, "Cutting", new BigDecimal("7"), "Draft");
        long instance = startAtBookHours();
        String book = CamundaSteps.awaitOpenTask(instance, BOOK_HOURS).path("userTaskKey").asText();
        HttpResponse<String> sent = flow.loggedInAs("supervisor").post("/api/v1/tasks/" + book + "/complete", toJson(Map.of("variables", Map.of())));
        assertThat(sent.statusCode()).as("send: " + sent.body()).isEqualTo(204);
        HttpResponse<String> validated = decide("timekeeper", awaitTask(instance, VALIDATE), "Validated", null);
        assertThat(validated.statusCode()).as("validate: " + validated.body()).isEqualTo(204);
        awaitTask(instance, APPROVE_PRODUCTION);
        return instance;
    }

    /** A record-hours-decision job of step ProductionSupervisor as the engine would hand it over. */
    private ActivatedJob job(long instance, long taskKey, String completedBy, String decision, String reason) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("hoursSubmissionId", jdbc.queryForObject("SELECT id FROM bpa.hours_submission WHERE process_instance_key = ? AND round_no = 1",
                UUID.class, instance).toString());
        variables.put("hoursDecision", decision);
        variables.put("hoursReason", reason);
        variables.put("completedBy", completedBy);
        variables.put("completedTaskKey", Long.toString(taskKey));
        ActivatedJob job = mock(ActivatedJob.class);
        when(job.getKey()).thenReturn(4_000_000_001L);
        when(job.getProcessInstanceKey()).thenReturn(instance);
        when(job.getElementId()).thenReturn("ServiceTask_RecordProductionDecision");
        when(job.getVariablesAsMap()).thenReturn(variables);
        when(job.getCustomHeaders()).thenReturn(Map.of("step", "ProductionSupervisor"));
        return job;
    }

    private String stage(long instance, int round) {
        return jdbc.queryForObject("SELECT stage FROM bpa.hours_submission WHERE process_instance_key = ? AND round_no = ?", String.class, instance, round);
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
