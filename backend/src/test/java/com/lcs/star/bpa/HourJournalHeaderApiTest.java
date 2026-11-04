package com.lcs.star.bpa;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcs.star.bpa.CamundaRest.Reply;
import com.lcs.star.bpa.api.TestBrowser;
import com.lcs.star.bpa.domain.WorkPackInput;
import com.lcs.star.bpa.persistence.WorkPackRepository;
import com.lcs.star.bpa.worker.HourJournalHeaderWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.StreamSupport;
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
 * BPA-7: the Project Hour Journal Header, one test per Gherkin scenario (the outline's rows inside its one test). The app runs
 * against the integration Camunda 8.10.1 with its job workers on, as {@code bpa-service}, as in WorkPackApiTest (BPA-6, the
 * new-job-worker skill). Background: today is 21 Oct 2026 (Asia/Riyadh), from a settable clock.
 *
 * <p>Two scenarios stand in for unbuilt stories (BPA-7 plan): the corrections save is BPA-23's, the sign-off lock is BPA-9's.
 * Each stand-in is marked, with what to change when that story lands.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(HourJournalHeaderApiTest.TestClockConfig.class)
class HourJournalHeaderApiTest {

    static final Instant TODAY = Instant.parse("2026-10-21T06:00:00Z"); // 09:00 in Riyadh
    static final String PROCESS = "Process_PPC_WorkPackAssignment";
    static final String CREATE = "UserTask_CreateWorkPack";
    static final String CONFIRM = "UserTask_ConfirmWorkPack";
    static final String UPDATE_HEADER = "ServiceTask_UpdateHourJournalHeader";
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
    WorkPackRepository workPacks;

    @Autowired
    HourJournalHeaderWorker headerWorker;

    @BeforeEach
    void today() {
        clock.set(TODAY);
    }

    @Test
    void BPA7_headerIsCreatedWithEstimatedManHours() {
        Saved wp = createWorkPack(List.of(phase("Cutting", 150), phase("Welding", 250)),
                List.of(material("MAT-PIPE-06", "Cutting"), material("MAT-FLG-02", "Welding")));

        JsonNode header = awaitHeader(wp.number(), h -> true);

        assertThat(header.path("workpackNumber").asText()).isEqualTo(wp.number());
        assertThat(header.path("estimatedManhours").decimalValue()).isEqualByComparingTo("400");
        assertThat(header.path("locked").asBoolean(true)).isFalse();

        // Added after review (L15, said in the PR): who and when (D63, S9) and the audit rows (D64, data model §6.1).
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT id, created_by, updated_by, created_at FROM bpa.hour_journal_header WHERE workpack_id = ?", wp.id());
        assertThat(row.get("created_by")).isEqualTo("time-method-analyst");
        assertThat(row.get("updated_by")).isEqualTo("time-method-analyst");
        assertThat(((java.sql.Timestamp) row.get("created_at")).toInstant()).isEqualTo(TODAY);
        List<Map<String, Object>> audit = jdbc.queryForList("""
                SELECT column_name, old_value, new_value, username, changed_at FROM bpa.audit_log
                WHERE table_name = 'hour_journal_header' AND record_id = ? ORDER BY column_name""", row.get("id"));
        assertThat(audit).extracting(r -> r.get("column_name") + "=" + r.get("new_value"))
                .containsExactly("estimated_manhours=400", "locked=false", "workpack_id=" + wp.id());
        assertThat(audit).allSatisfy(r -> {
            assertThat(r.get("old_value")).isNull();
            assertThat(r.get("username")).isEqualTo("time-method-analyst");
            assertThat(((java.sql.Timestamp) r.get("changed_at")).toInstant()).isEqualTo(TODAY);
        });
    }

    @Test
    void BPA7_headerIsNotDuplicatedOnRerun() {
        // The job runs twice for one instance, as a retry after a lost completion would (new-job-worker skill). A key from
        // partition 2's range, which the single-partition test Camunda never generates itself.
        long instance = 4503599627370501L;
        var saved = workPacks.save(instance, new WorkPackInput("A-2201", "DWG-118", "Fabrication", LocalDate.parse("2026-11-06"),
                List.of(new WorkPackInput.Phase("Cutting", new BigDecimal("150")), new WorkPackInput.Phase("Welding", new BigDecimal("250"))),
                List.of(new WorkPackInput.Material("MAT-PIPE-06", "Cutting"))), "time-method-analyst");
        JobClient client = Mockito.mock(JobClient.class, Mockito.RETURNS_DEEP_STUBS);

        headerWorker.handle(client, job(11L, instance, "ServiceTask_CreateHourJournalHeader"));
        int auditRows = headerAuditRows(saved.id());
        headerWorker.handle(client, job(12L, instance, "ServiceTask_CreateHourJournalHeader"));

        Mockito.verify(client).newCompleteCommand(11L);
        Mockito.verify(client).newCompleteCommand(12L);
        List<JsonNode> headers = headers(ppcTeam(), saved.workpackNumber());
        assertThat(headers).as("exactly one Header for " + saved.workpackNumber()).hasSize(1);
        assertThat(headers.get(0).path("estimatedManhours").decimalValue()).isEqualByComparingTo("400");
        assertThat(headerAuditRows(saved.id())).as("no audit row for an unchanged re-run (added after review)").isEqualTo(auditRows);
    }

    @Test
    void BPA7_decimalManHoursAreTotalledExactly() {
        // Examples: | Cutting 150.5, Welding 249.5 | 400 |
        Saved halves = createWorkPack(List.of(phase("Cutting", new BigDecimal("150.5")), phase("Welding", new BigDecimal("249.5"))),
                List.of(material("MAT-PIPE-06", "Cutting"), material("MAT-FLG-02", "Welding")));
        // Examples: | Engineering 0.01 | 0.01 |
        Saved hundredth = createWorkPack(List.of(phase("Engineering", new BigDecimal("0.01"))),
                List.of(material("MAT-PIPE-06", "Engineering")));

        assertThat(awaitHeader(halves.number(), h -> true).path("estimatedManhours").decimalValue()).isEqualByComparingTo("400");
        assertThat(awaitHeader(hundredth.number(), h -> true).path("estimatedManhours").decimalValue()).isEqualByComparingTo("0.01");
    }

    @Test
    void BPA7_headerFollowsCorrectionsWhileDraft() {
        Saved wp = createWorkPack(List.of(phase("Cutting", 150), phase("Welding", 250)),
                List.of(material("MAT-PIPE-06", "Cutting"), material("MAT-FLG-02", "Welding")));
        assertThat(awaitHeader(wp.number(), h -> true).path("estimatedManhours").decimalValue()).isEqualByComparingTo("400");

        correctWeldingTo300(wp);

        JsonNode header = awaitHeader(wp.number(), h -> h.path("estimatedManhours").decimalValue().compareTo(new BigDecimal("450")) == 0);
        assertThat(headers(ppcTeam(), wp.number())).as("exactly one Header").hasSize(1);
        assertThat(header.path("estimatedManhours").decimalValue()).isEqualByComparingTo("450");
        assertThat(header.path("locked").asBoolean(true)).isFalse();

        // Added after review (L15): the update is BPA-7's own write, so its audit row is asserted now (D64, §6.1).
        // The username is the last task completer's: today Create Work Pack's; with BPA-23, Confirm Workpack's (both TMA).
        assertThat(jdbc.queryForList("""
                SELECT a.old_value || '->' || a.new_value || ' by ' || a.username FROM bpa.audit_log a
                JOIN bpa.hour_journal_header h ON h.id = a.record_id
                WHERE a.table_name = 'hour_journal_header' AND a.column_name = 'estimated_manhours' AND a.old_value IS NOT NULL
                  AND h.workpack_id = ?""", String.class, wp.id())).containsExactly("400->450 by time-method-analyst");
    }

    @Test
    void BPA7_headerIsLockedOnceReleased() {
        Saved wp = createWorkPack(List.of(phase("Cutting", 150), phase("Welding", 250)),
                List.of(material("MAT-PIPE-06", "Cutting"), material("MAT-FLG-02", "Welding")));
        awaitHeader(wp.number(), h -> true);
        correctWeldingTo300(wp);
        awaitHeader(wp.number(), h -> h.path("estimatedManhours").decimalValue().compareTo(new BigDecimal("450")) == 0);

        // Stand-in for BPA-9's sign-off (record-release-signoff sets Released and locks the Header, D8), which has no worker
        // yet. The token is moved past Confirm from wherever it is: today it waits at ServiceTask_EvaluateReleaseInputs;
        // once BPA-8 has a worker it moves on by itself, to Sign off release or (Not ready) back to Confirm Workpack.
        // BPA-8: nothing to change here; the stand-in terminates whatever is active.
        // BPA-9: replace these three lines with the real path. Confirm (BPA-8/23) → release check Ready, which needs reserved
        // materials and an approved drawing for DWG-118 / MAT-PIPE-06 / MAT-FLG-02 (D1, D10) → the Project Engineer approves
        // Sign off release through the task API. Keep every assertion below, including the locked re-run (BPA-7's own part).
        jdbc.update("UPDATE bpa.workpack SET status = 'Released' WHERE id = ?", wp.id());
        jdbc.update("UPDATE bpa.hour_journal_header SET locked = true WHERE workpack_id = ?", wp.id());
        moveTokenTo(wp.instance(), "UserTask_ScheduleWorkPack");

        JsonNode header = headers(ppcTeam(), wp.number()).get(0);
        assertThat(header.path("locked").asBoolean(false)).isTrue();
        assertThat(header.path("estimatedManhours").decimalValue()).isEqualByComparingTo("450");
        // Today this holds already (the corrections stand-in ended Confirm); it gains meaning with BPA-9's real sign-off.
        // Polled for a few seconds, since the task search is eventually consistent (BPA-25).
        assertStaysEmpty(() -> openTasks(wp.instance(), CONFIRM), "Confirm Workpack task for " + wp.number());

        // BPA-7's own part of D8: the upsert never changes a locked Header, even if the phases change later.
        int auditRows = headerAuditRows(wp.id());
        jdbc.update("UPDATE bpa.workpack_phase SET manhours = 999 WHERE workpack_id = ? AND phase = 'Cutting'", wp.id());
        JobClient client = Mockito.mock(JobClient.class, Mockito.RETURNS_DEEP_STUBS);
        headerWorker.handle(client, job(21L, wp.instance(), UPDATE_HEADER));
        Mockito.verify(client).newCompleteCommand(21L);
        assertThat(headers(ppcTeam(), wp.number()).get(0).path("estimatedManhours").decimalValue()).isEqualByComparingTo("450");
        assertThat(headerAuditRows(wp.id())).as("no audit row for a locked Header").isEqualTo(auditRows);
    }

    /** Not a scenario: the contract's 400 (InvalidWorkpackNumber) for a number outside WP-###### or none at all. */
    @Test
    void invalidOrMissingWorkpackNumberIs400() {
        TestBrowser ppc = ppcTeam();
        for (String query : List.of("?workpackNumber=WP-12", "")) {
            HttpResponse<String> response = ppc.get("/api/v1/hour-journal-headers" + query);
            assertThat(response.statusCode()).as("'" + query + "': " + response.body()).isEqualTo(400);
            assertThat(json(response.body()).path("detail").asText()).isEqualTo("Workpack number must match WP-######");
        }
    }

    @Test
    void BPA7_unknownWorkPackReturnsNotFound() {
        HttpResponse<String> response = ppcTeam().get("/api/v1/hour-journal-headers?workpackNumber=WP-999999");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(t -> assertThat(t).startsWith("application/problem+json"));
        assertThat(json(response.body()).path("detail").asText()).isEqualTo("Work Pack not found");
    }

    @Test
    void BPA7_otherRolesCannotReadHeader() {
        Saved wp = createWorkPack(List.of(phase("Cutting", 150), phase("Welding", 250)),
                List.of(material("MAT-PIPE-06", "Cutting"), material("MAT-FLG-02", "Welding")));
        awaitHeader(wp.number(), h -> true); // a Header exists, so the 403 is about the group, not missing data

        HttpResponse<String> response = loggedInAs("production-planner").get("/api/v1/hour-journal-headers?workpackNumber=" + wp.number());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).doesNotContain("estimatedManhours").doesNotContain(wp.number());
        // Added after review (L15): the 403 is the contract's Forbidden problem, and the other allowed group can read it.
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(t -> assertThat(t).startsWith("application/problem+json"));
        assertThat(json(response.body()).path("status").asInt()).isEqualTo(403);
        assertThat(headers(loggedInAs("time-method-analyst"), wp.number())).hasSize(1);
    }

    // --- the real corrections step (BPA-23) --------------------------------------------------------------------------------

    /**
     * "The Time & Method Analyst changes Welding from 250 to 300 in the Confirm Workpack task", now through the real path
     * (BPA-23 re-check; until BPA-23 this was a stand-in that set the phase in the database and moved the token). The Analyst
     * opens the Confirm task, keeps its variables (with the material line ids) and completes it with Welding 300; then
     * ServiceTask_SaveWorkPackCorrections and ServiceTask_UpdateHourJournalHeader run for real.
     * BPA-8: nothing to change; Confirm is still where the token is when this runs.
     */
    @SuppressWarnings("unchecked")
    private void correctWeldingTo300(Saved wp) {
        String confirm = awaitOpenTask(wp.instance(), CONFIRM).path("userTaskKey").asText();
        TestBrowser analyst = loggedInAs("time-method-analyst");
        Map<String, Object> variables = JSON.convertValue(json(analyst.get("/api/v1/tasks/" + confirm).body()).path("variables"), Map.class);
        List<Map<String, Object>> phases = new java.util.ArrayList<>();
        for (Object p : (List<Object>) variables.get("phases")) {
            Map<String, Object> line = new LinkedHashMap<>((Map<String, Object>) p);
            if ("Welding".equals(line.get("phase"))) {
                line.put("manhours", 300);
            }
            phases.add(line);
        }
        variables.put("phases", phases);
        HttpResponse<String> done = analyst.post("/api/v1/tasks/" + confirm + "/complete", toJson(Map.of("variables", variables)));
        assertThat(done.statusCode()).as("complete Confirm: " + done.body()).isEqualTo(204);
    }

    // --- the portal's steps and Camunda helpers -------------------------------------------------------------------------

    record Saved(long instance, java.util.UUID id, String number) {
    }

    /** Starts a request and completes Create Work Pack as the Analyst; waits for the save step's Work Pack. */
    private Saved createWorkPack(List<Map<String, Object>> phases, List<Map<String, Object>> materials) {
        TestBrowser analyst = loggedInAs("time-method-analyst");
        HttpResponse<String> started = analyst.post("/api/v1/workpacks", "");
        assertThat(started.statusCode()).as("POST /api/v1/workpacks: " + started.body()).isEqualTo(201);
        String instance = json(started.body()).path("processInstanceKey").asText();
        String task = awaitOpenTask(Long.parseLong(instance), CREATE).path("userTaskKey").asText();
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("activityRef", "A-2201");
        variables.put("drawingRef", "DWG-118");
        variables.put("phases", phases);
        variables.put("materials", materials);
        variables.put("departmentRef", "Fabrication");
        variables.put("completionDate", "2026-11-06");
        HttpResponse<String> completed = analyst.post("/api/v1/tasks/" + task + "/complete", toJson(Map.of("variables", variables)));
        assertThat(completed.statusCode()).as("complete: " + completed.body()).isEqualTo(204);
        long key = Long.parseLong(instance);
        Map<String, Object> row = poll(() -> jdbc.queryForList("SELECT id, workpack_number FROM bpa.workpack WHERE process_instance_key = ?", key),
                rows -> !rows.isEmpty(), "Work Pack saved for " + instance).get(0);
        return new Saved(key, (java.util.UUID) row.get("id"), (String) row.get("workpack_number"));
    }

    private List<JsonNode> headers(TestBrowser browser, String workpackNumber) {
        HttpResponse<String> response = browser.get("/api/v1/hour-journal-headers?workpackNumber=" + workpackNumber);
        assertThat(response.statusCode()).as("GET header: " + response.body()).isEqualTo(200);
        return elements(json(response.body()));
    }

    private JsonNode awaitHeader(String workpackNumber, Predicate<JsonNode> done) {
        TestBrowser ppc = ppcTeam();
        return poll(() -> headers(ppc, workpackNumber), list -> list.size() == 1 && done.test(list.get(0)),
                "Header of " + workpackNumber).get(0);
    }

    private JsonNode awaitOpenTask(long instance, String elementId) {
        return poll(() -> openTasks(instance, elementId), list -> !list.isEmpty(), elementId + " of " + instance).get(0);
    }

    private static List<JsonNode> openTasks(long instance, String elementId) {
        Reply found = IntegrationApp.admin.post("/v2/user-tasks/search",
                Map.of("filter", Map.of("processInstanceKey", Long.toString(instance), "elementId", elementId, "state", "CREATED")));
        return elements(found.body().path("items"));
    }

    /**
     * Terminate whatever is active in the instance and activate one element (test stand-in). Retried, because the search may
     * still list an element the engine has already left (eventual consistency, BPA-25): the engine then rejects it.
     */
    private static void moveTokenTo(long instance, String toElementId) {
        long until = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        Reply last = null;
        while (System.nanoTime() < until) {
            List<JsonNode> active = activeElements(instance);
            if (!active.isEmpty()) {
                last = IntegrationApp.admin.post("/v2/process-instances/" + instance + "/modification", modification(active, toElementId));
                if (last.status() == 204) {
                    return;
                }
            }
            pause();
        }
        throw new AssertionError("could not move " + instance + " to " + toElementId + "; last: " + (last == null ? "nothing active" : last.body()));
    }

    private static Map<String, Object> modification(List<JsonNode> terminate, String toElementId) {
        return Map.of(
                "activateInstructions", List.of(Map.of("elementId", toElementId)),
                "terminateInstructions", terminate.stream().map(e -> Map.of("elementInstanceKey", e.path("elementInstanceKey").asText())).toList());
    }

    /** The instance's active flow elements (not the process itself). */
    private static List<JsonNode> activeElements(long instance) {
        return elements(IntegrationApp.admin.post("/v2/element-instances/search",
                        Map.of("filter", Map.of("processInstanceKey", Long.toString(instance), "state", "ACTIVE"), "page", Map.of("limit", 50)))
                .body().path("items")).stream()
                .filter(e -> !"PROCESS".equals(e.path("type").asText()) && !PROCESS.equals(e.path("elementId").asText()))
                .toList();
    }

    private static <T> void assertStaysEmpty(java.util.function.Supplier<List<T>> read, String what) {
        long until = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < until) {
            assertThat(read.get()).as("no " + what).isEmpty();
            pause();
        }
    }

    private int headerAuditRows(java.util.UUID workpackId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM bpa.audit_log a JOIN bpa.hour_journal_header h ON h.id = a.record_id
                WHERE a.table_name = 'hour_journal_header' AND h.workpack_id = ?""", Integer.class, workpackId);
    }

    private static void pause() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private TestBrowser ppcTeam() {
        return loggedInAs("ppc-team");
    }

    private TestBrowser loggedInAs(String username) {
        TestBrowser browser = new TestBrowser(port);
        HttpResponse<String> login = browser.login(username, PersonaSyncTest.DEMO_PASSWORD);
        assertThat(login.statusCode()).as("login " + username + ": " + login.body()).isEqualTo(200);
        return browser;
    }

    private static ActivatedJob job(long key, long instance, String elementId) {
        ActivatedJob job = Mockito.mock(ActivatedJob.class);
        Mockito.when(job.getKey()).thenReturn(key);
        Mockito.when(job.getProcessInstanceKey()).thenReturn(instance);
        Mockito.when(job.getElementId()).thenReturn(elementId);
        Mockito.when(job.getVariablesAsMap()).thenReturn(Map.of("completedBy", "time-method-analyst"));
        return job;
    }

    private static Map<String, Object> phase(String phase, Object manhours) {
        return Map.of("phase", phase, "manhours", manhours);
    }

    private static Map<String, Object> material(String materialRef, String phase) {
        return Map.of("materialRef", materialRef, "phase", phase);
    }

    private static <T> T poll(java.util.function.Supplier<T> read, Predicate<T> done, String what) {
        long until = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        T last = read.get();
        while (!done.test(last)) {
            if (System.nanoTime() > until) {
                throw new AssertionError("timed out waiting for " + what + "; last: " + last);
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            last = read.get();
        }
        return last;
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode json(String body) {
        try {
            return JSON.readTree(body);
        } catch (java.io.IOException e) {
            throw new AssertionError("not JSON: " + body, e);
        }
    }

    private static List<JsonNode> elements(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).toList();
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
