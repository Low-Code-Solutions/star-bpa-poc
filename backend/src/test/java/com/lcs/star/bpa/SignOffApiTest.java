package com.lcs.star.bpa;

import static com.lcs.star.bpa.CamundaSteps.assertStaysEmpty;
import static com.lcs.star.bpa.CamundaSteps.awaitOpenTask;
import static com.lcs.star.bpa.CamundaSteps.awaitSignOffTask;
import static com.lcs.star.bpa.CamundaSteps.completeSignOff;
import static com.lcs.star.bpa.CamundaSteps.json;
import static com.lcs.star.bpa.CamundaSteps.openTasks;
import static com.lcs.star.bpa.CamundaSteps.poll;
import static com.lcs.star.bpa.CamundaSteps.toJson;
import static com.lcs.star.bpa.CamundaSteps.variable;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcs.star.bpa.api.TestBrowser;
import com.lcs.star.bpa.worker.RecordSignOffWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BPA-9: Sign off release, one test per Gherkin scenario, through the real path: create the Work Pack, complete Confirm
 * Workpack unchanged, the release check returns Ready (the seed's DWG-118, IFC, with MAT-PIPE-06 reserved by TR-5531), and the
 * process waits at UserTask_SignOffRelease. The app runs against the integration Camunda 8.10.1 with its job workers on, as in
 * ReleaseCheckApiTest (BPA-8). Background: the Project Engineer is {@code project-engineer} (BPA-44).
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(SignOffApiTest.TestClockConfig.class)
class SignOffApiTest {

    static final Instant TODAY = Instant.parse("2026-10-21T06:00:00Z"); // 09:00 in Riyadh
    static final String CREATE = "UserTask_CreateWorkPack";
    static final String CONFIRM = "UserTask_ConfirmWorkPack";
    static final String SCHEDULE = "UserTask_ScheduleWorkPack";
    static final String DECLINED_PREFIX = "Declined by Project Engineer: ";
    private static final ObjectMapper JSON = new ObjectMapper();

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
    TestClock clock;

    @Autowired
    RecordSignOffWorker worker;

    @BeforeEach
    void today() {
        clock.set(TODAY);
    }

    // --- the scenarios ----------------------------------------------------------------------------------------------------

    @Test
    void BPA9_releaseIsSignedOff() {
        Waiting wp = atSignOff();

        HttpResponse<String> done = completeSignOff(loggedInAs("project-engineer"), wp.signOffTask(), "Approved", null);
        assertThat(done.statusCode()).as("approve: " + done.body()).isEqualTo(204);

        Map<String, Object> row = awaitSignOffRow(wp);
        assertThat(status(wp)).as(wp.number() + " is Released").isEqualTo("Released");
        assertThat(row.get("decision")).isEqualTo("Approved");
        assertThat(row.get("decline_reason")).isNull();
        assertThat(row.get("signed_off_by")).as("the Camunda username, D63").isEqualTo("project-engineer");
        assertThat(((java.sql.Timestamp) row.get("signed_off_at")).toInstant()).as("sign-off date from the clock, S9").isEqualTo(TODAY);
        assertThat(row.get("task_key")).as("the re-run key (D65)").isEqualTo(Long.parseLong(wp.signOffTask()));
        assertThat(headerLocked(wp)).as("man hours locked (D8)").isTrue();

        // The audit rows (D64, data model §6.1): one per changed business column, by the Project Engineer.
        UUID signOffId = (UUID) row.get("id");
        assertThat(jdbc.queryForList("""
                SELECT column_name || '=' || new_value FROM bpa.audit_log
                WHERE table_name = 'release_signoff' AND record_id = ? AND old_value IS NULL AND username = 'project-engineer'
                ORDER BY column_name""", String.class, signOffId))
                .containsExactly("decision=Approved", "signed_off_at=2026-10-21T06:00:00Z", "signed_off_by=project-engineer",
                        "task_key=" + wp.signOffTask(), "workpack_id=" + wp.id());
        assertThat(jdbc.queryForList("""
                SELECT old_value || '->' || new_value || ' by ' || username FROM bpa.audit_log
                WHERE table_name = 'workpack' AND column_name = 'status' AND record_id = ? AND old_value IS NOT NULL""",
                String.class, wp.id())).containsExactly("Draft->Released by project-engineer");
        assertThat(jdbc.queryForList("""
                SELECT a.old_value || '->' || a.new_value || ' by ' || a.username FROM bpa.audit_log a
                JOIN bpa.hour_journal_header h ON h.id = a.record_id
                WHERE a.table_name = 'hour_journal_header' AND a.column_name = 'locked' AND a.old_value IS NOT NULL AND h.workpack_id = ?""",
                String.class, wp.id())).containsExactly("false->true by project-engineer");
    }

    @Test
    void BPA9_declineReturnsToAnalyst() {
        Waiting wp = atSignOff();

        HttpResponse<String> done = completeSignOff(loggedInAs("project-engineer"), wp.signOffTask(), "Declined", "Wrong drawing revision");
        assertThat(done.statusCode()).as("decline: " + done.body()).isEqualTo(204);

        // The Confirm Workpack task returns to the Analyst, showing the reason (D3, D51).
        JsonNode back = poll(() -> openTasks(wp.instance(), CONFIRM).stream()
                .filter(t -> !wp.confirmTask().equals(t.path("userTaskKey").asText())).toList(), list -> !list.isEmpty(),
                "Confirm Workpack returned for " + wp.number()).get(0);
        TestBrowser analyst = loggedInAs("time-method-analyst");
        JsonNode task = json(analyst.get("/api/v1/tasks/" + back.path("userTaskKey").asText()).body());
        assertThat(task.path("confirmMessage").asText()).isEqualTo(DECLINED_PREFIX + "Wrong drawing revision");
        assertThat(status(wp)).as(wp.number() + " stays Draft").isEqualTo("Draft");
        assertThat(headerLocked(wp)).as("man hours stay open").isFalse();
        Map<String, Object> row = awaitSignOffRow(wp);
        assertThat(row.get("decision")).isEqualTo("Declined");
        assertThat(row.get("decline_reason")).isEqualTo("Wrong drawing revision");
        assertThat(row.get("signed_off_by")).isEqualTo("project-engineer");

        // "And the release check runs again when the Analyst confirms": completed unchanged, the check runs and a new sign-off
        // task opens.
        completeUnchanged(analyst, back.path("userTaskKey").asText());
        String second = poll(() -> openTasks(wp.instance(), "UserTask_SignOffRelease").stream()
                .map(t -> t.path("userTaskKey").asText()).filter(k -> !k.equals(wp.signOffTask())).toList(), list -> !list.isEmpty(),
                "a new sign-off task after the release check re-ran").get(0);
        assertThat(second).isNotEqualTo(wp.signOffTask());
    }

    @Test
    void BPA9_declineNeedsReason() {
        Waiting wp = atSignOff();
        TestBrowser engineer = loggedInAs("project-engineer");

        // "When I decline the sign-off without a reason": no reason at all, and a reason of only blanks.
        for (String reason : new String[] {null, "", "   "}) {
            HttpResponse<String> response = completeSignOff(engineer, wp.signOffTask(), "Declined", reason);
            assertThat(response.statusCode()).as("reason '" + reason + "': " + response.body()).isEqualTo(400);
            assertThat(json(response.body()).path("detail").asText()).isEqualTo("Reason is required");
        }

        assertThat(openTasks(wp.instance(), "UserTask_SignOffRelease")).as("the task still awaits sign-off").hasSize(1);
        assertStaysEmpty(() -> jdbc.queryForList("SELECT id FROM bpa.release_signoff WHERE workpack_id = ?", wp.id()),
                "release_signoff row for " + wp.number());
        assertThat(status(wp)).isEqualTo("Draft");
    }

    @Test
    void BPA9_workPackWaitsForSignOff() {
        // BPA-8 built the wait; this scenario is BPA-9's AC, so it is asserted here. Expected to pass before the worker exists.
        Waiting wp = atSignOff();

        assertThat(status(wp)).as(wp.number() + " stays Draft").isEqualTo("Draft");
        assertThat(CamundaSteps.activeElements(wp.instance())).extracting(e -> e.path("elementId").asText())
                .as("the instance waits at UserTask_SignOffRelease").containsExactly("UserTask_SignOffRelease");
        assertStaysEmpty(() -> openTasks(wp.instance(), SCHEDULE), "Schedule Work Pack task for " + wp.number());
    }

    @Test
    void BPA9_otherRolesCannotSignOff() {
        Waiting wp = atSignOff();
        TestBrowser analyst = loggedInAs("time-method-analyst");

        // "When I call GET /api/v1/tasks": the sign-off task is not listed.
        HttpResponse<String> list = analyst.get("/api/v1/tasks");
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(CamundaSteps.elements(json(list.body()))).extracting(t -> t.path("key").asText()).doesNotContain(wp.signOffTask());

        // "And POST /api/v1/tasks/{key}/complete for that task returns 403."
        HttpResponse<String> response = completeSignOff(analyst, wp.signOffTask(), "Approved", null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
        assertThat(openTasks(wp.instance(), "UserTask_SignOffRelease")).as("still awaits the Project Engineer").hasSize(1);
        assertThat(status(wp)).isEqualTo("Draft");
    }

    @Test
    void BPA9_rerunRecordsSignOffOnce() {
        Waiting wp = atSignOff();
        assertThat(completeSignOff(loggedInAs("project-engineer"), wp.signOffTask(), "Approved", null).statusCode()).isEqualTo(204);
        awaitSignOffRow(wp);
        int auditRows = signOffAuditRows(wp);
        JobClient client = Mockito.mock(JobClient.class, Mockito.RETURNS_DEEP_STUBS);

        // The job for the same task runs again, as after a lost completion (09 S7): same task key, same user, same decision.
        worker.handle(client, job(41L, wp.instance(), Map.of("completedTaskKey", wp.signOffTask(), "completedBy", "project-engineer",
                "signOffDecision", "Approved")));
        worker.handle(client, job(42L, wp.instance(), Map.of("completedTaskKey", wp.signOffTask(), "completedBy", "project-engineer",
                "signOffDecision", "Approved")));

        Mockito.verify(client).newCompleteCommand(41L);
        Mockito.verify(client).newCompleteCommand(42L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.release_signoff WHERE workpack_id = ?", Integer.class, wp.id()))
                .as("exactly one decision for the task").isEqualTo(1);
        assertThat(status(wp)).isEqualTo("Released");
        assertThat(headerLocked(wp)).isTrue();
        assertThat(signOffAuditRows(wp)).as("no audit row for a re-run").isEqualTo(auditRows);
    }

    // --- not scenarios ----------------------------------------------------------------------------------------------------

    /** The backend re-check also covers a missing or unknown decision (ASSUMPTION texts; the gateway's default is Declined). */
    @Test
    void signOffNeedsADecision() {
        Waiting wp = atSignOff();
        TestBrowser engineer = loggedInAs("project-engineer");

        HttpResponse<String> none = completeSignOff(engineer, wp.signOffTask(), null, null);
        assertThat(none.statusCode()).as(none.body()).isEqualTo(400);
        assertThat(json(none.body()).path("detail").asText()).isEqualTo("Decision is required");
        HttpResponse<String> unknown = completeSignOff(engineer, wp.signOffTask(), "Maybe", null);
        assertThat(unknown.statusCode()).as(unknown.body()).isEqualTo(400);
        assertThat(json(unknown.body()).path("detail").asText()).isEqualTo("Decision must be Approved or Declined");
        assertThat(openTasks(wp.instance(), "UserTask_SignOffRelease")).hasSize(1);
    }

    /** completedTaskKey is set from the engine's task on every completion (like completedBy, D63); a client cannot send it. */
    @Test
    void completedTaskKeyIsSetByTheServer() {
        Waiting wp = atSignOff();
        TestBrowser engineer = loggedInAs("project-engineer");

        HttpResponse<String> forged = engineer.post("/api/v1/tasks/" + wp.signOffTask() + "/complete",
                toJson(Map.of("variables", Map.of("signOffDecision", "Approved", "completedTaskKey", "1"))));
        assertThat(forged.statusCode()).as(forged.body()).isEqualTo(400);
        assertThat(json(forged.body()).path("detail").asText()).isEqualTo("Variable completedTaskKey is not allowed for this task");

        assertThat(completeSignOff(engineer, wp.signOffTask(), "Approved", null).statusCode()).isEqualTo(204);
        String value = poll(() -> variable(wp.instance(), "completedTaskKey"), v -> v != null, "completedTaskKey of " + wp.instance());
        assertThat(value.replace("\"", "")).isEqualTo(wp.signOffTask());
    }

    // --- the Background: a Work Pack that passed the release check and awaits sign-off ----------------------------------------

    record Waiting(long instance, UUID id, String number, String confirmTask, String signOffTask) {
    }

    private Waiting atSignOff() {
        TestBrowser analyst = loggedInAs("time-method-analyst");
        HttpResponse<String> started = analyst.post("/api/v1/workpacks", "");
        assertThat(started.statusCode()).as("POST /api/v1/workpacks: " + started.body()).isEqualTo(201);
        long instance = Long.parseLong(json(started.body()).path("processInstanceKey").asText());
        String create = awaitOpenTask(instance, CREATE).path("userTaskKey").asText();
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("activityRef", "A-2201");
        variables.put("drawingRef", "DWG-118");
        variables.put("phases", List.of(Map.of("phase", "Cutting", "manhours", 150), Map.of("phase", "Welding", "manhours", 250)));
        variables.put("materials", List.of(Map.of("materialRef", "MAT-PIPE-06", "phase", "Cutting")));
        variables.put("departmentRef", "Fabrication");
        variables.put("completionDate", "2026-11-06");
        HttpResponse<String> done = analyst.post("/api/v1/tasks/" + create + "/complete", toJson(Map.of("variables", variables)));
        assertThat(done.statusCode()).as("complete Create: " + done.body()).isEqualTo(204);
        String confirm = awaitOpenTask(instance, CONFIRM).path("userTaskKey").asText();
        completeUnchanged(analyst, confirm);
        String signOff = awaitSignOffTask(instance);
        Map<String, Object> row = jdbc.queryForMap("SELECT id, workpack_number FROM bpa.workpack WHERE process_instance_key = ?", instance);
        return new Waiting(instance, (UUID) row.get("id"), (String) row.get("workpack_number"), confirm, signOff);
    }

    /** Completes a Confirm Workpack task with exactly the variables it shows: nothing changes (BPA-23). */
    private void completeUnchanged(TestBrowser analyst, String confirmTask) {
        HttpResponse<String> open = analyst.get("/api/v1/tasks/" + confirmTask);
        assertThat(open.statusCode()).as("GET task: " + open.body()).isEqualTo(200);
        Map<String, Object> variables = JSON.convertValue(json(open.body()).path("variables"), new TypeReference<Map<String, Object>>() {
        });
        HttpResponse<String> done = analyst.post("/api/v1/tasks/" + confirmTask + "/complete", toJson(Map.of("variables", variables)));
        assertThat(done.statusCode()).as("complete Confirm: " + done.body()).isEqualTo(204);
    }

    private Map<String, Object> awaitSignOffRow(Waiting wp) {
        return poll(() -> jdbc.queryForList("SELECT * FROM bpa.release_signoff WHERE workpack_id = ? ORDER BY created_at", wp.id()),
                rows -> !rows.isEmpty(), "release_signoff row for " + wp.number()).get(0);
    }

    private String status(Waiting wp) {
        return jdbc.queryForObject("SELECT status FROM bpa.workpack WHERE id = ?", String.class, wp.id());
    }

    private boolean headerLocked(Waiting wp) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT locked FROM bpa.hour_journal_header WHERE workpack_id = ?", Boolean.class, wp.id()));
    }

    /** Audit rows the sign-off could add: the decision's own, the Work Pack's status and the Header's lock. */
    private int signOffAuditRows(Waiting wp) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM bpa.audit_log a
                WHERE (a.table_name = 'release_signoff' AND a.record_id IN (SELECT id FROM bpa.release_signoff WHERE workpack_id = ?))
                   OR (a.table_name = 'workpack' AND a.record_id = ? AND a.column_name = 'status')
                   OR (a.table_name = 'hour_journal_header' AND a.record_id IN (SELECT id FROM bpa.hour_journal_header WHERE workpack_id = ?))""",
                Integer.class, wp.id(), wp.id(), wp.id());
    }

    private TestBrowser loggedInAs(String username) {
        TestBrowser browser = new TestBrowser(port);
        HttpResponse<String> login = browser.login(username, PersonaSyncTest.DEMO_PASSWORD);
        assertThat(login.statusCode()).as("login " + username + ": " + login.body()).isEqualTo(200);
        return browser;
    }

    private static ActivatedJob job(long key, long instance, Map<String, Object> variables) {
        ActivatedJob job = Mockito.mock(ActivatedJob.class);
        Mockito.when(job.getKey()).thenReturn(key);
        Mockito.when(job.getProcessInstanceKey()).thenReturn(instance);
        Mockito.when(job.getElementId()).thenReturn("ServiceTask_RecordSignOff");
        Mockito.when(job.getVariablesAsMap()).thenReturn(variables);
        return job;
    }

    /** "Given today is 21 Oct 2026 (Asia/Riyadh)" (S9; ADR 0013). */
    static final class TestClock extends Clock {
        private volatile Instant now = TODAY;

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock();
        }
    }
}
