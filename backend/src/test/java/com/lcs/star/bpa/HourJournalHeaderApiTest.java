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
        headerWorker.handle(client, job(12L, instance, "ServiceTask_CreateHourJournalHeader"));

        Mockito.verify(client).newCompleteCommand(11L);
        Mockito.verify(client).newCompleteCommand(12L);
        List<JsonNode> headers = headers(ppcTeam(), saved.workpackNumber());
        assertThat(headers).as("exactly one Header for " + saved.workpackNumber()).hasSize(1);
        assertThat(headers.get(0).path("estimatedManhours").decimalValue()).isEqualByComparingTo("400");
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
    }

    @Test
    void BPA7_headerIsLockedOnceReleased() {
        Saved wp = createWorkPack(List.of(phase("Cutting", 150), phase("Welding", 250)),
                List.of(material("MAT-PIPE-06", "Cutting"), material("MAT-FLG-02", "Welding")));
        awaitHeader(wp.number(), h -> true);
        correctWeldingTo300(wp);
        awaitHeader(wp.number(), h -> h.path("estimatedManhours").decimalValue().compareTo(new BigDecimal("450")) == 0);

        // Stand-in for BPA-9's sign-off (record-release-signoff sets Released and locks the Header, D8), which has no worker
        // yet. BPA-9: replace these lines with the real path (Confirm → release check → the Project Engineer approves Sign off
        // release through the task API) and keep the assertions below.
        jdbc.update("UPDATE bpa.workpack SET status = 'Released' WHERE id = ?", wp.id());
        jdbc.update("UPDATE bpa.hour_journal_header SET locked = true WHERE workpack_id = ?", wp.id());
        moveToken(wp.instance(), "ServiceTask_EvaluateReleaseInputs", "UserTask_ScheduleWorkPack");

        JsonNode header = headers(ppcTeam(), wp.number()).get(0);
        assertThat(header.path("locked").asBoolean(false)).isTrue();
        assertThat(header.path("estimatedManhours").decimalValue()).isEqualByComparingTo("450");
        assertThat(openTasks(wp.instance(), CONFIRM)).as("no Confirm Workpack task for " + wp.number()).isEmpty();

        // BPA-7's own part of D8: the upsert never changes a locked Header, even if the phases change later.
        jdbc.update("UPDATE bpa.workpack_phase SET manhours = 999 WHERE workpack_id = ? AND phase = 'Cutting'", wp.id());
        headerWorker.handle(Mockito.mock(JobClient.class, Mockito.RETURNS_DEEP_STUBS), job(21L, wp.instance(), UPDATE_HEADER));
        assertThat(headers(ppcTeam(), wp.number()).get(0).path("estimatedManhours").decimalValue()).isEqualByComparingTo("450");
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
    }

    // --- stand-in for BPA-23 --------------------------------------------------------------------------------------------

    /**
     * "The Time & Method Analyst changes Welding from 250 to 300 in the Confirm Workpack task". The corrections save is BPA-23's
     * (today save-workpack refuses those jobs with an incident, BPA-6). So the test changes the phase as that save will, and
     * moves the token from Confirm Workpack to ServiceTask_UpdateHourJournalHeader, where the real worker runs.
     * BPA-23: replace this with completing the Confirm task through the task API with Welding 300, and add an assertion for
     * the audit_log row 400 -> 450.
     */
    private void correctWeldingTo300(Saved wp) {
        awaitOpenTask(wp.instance(), CONFIRM);
        jdbc.update("UPDATE bpa.workpack_phase SET manhours = 300 WHERE workpack_id = ? AND phase = 'Welding'", wp.id());
        moveToken(wp.instance(), CONFIRM, UPDATE_HEADER);
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

    /** Process instance modification as the demo admin: terminate the active element, activate another (test stand-in). */
    private static void moveToken(long instance, String fromElementId, String toElementId) {
        JsonNode active = poll(() -> elements(IntegrationApp.admin.post("/v2/element-instances/search",
                        Map.of("filter", Map.of("processInstanceKey", Long.toString(instance), "elementId", fromElementId, "state", "ACTIVE")))
                        .body().path("items")),
                list -> !list.isEmpty(), fromElementId + " active in " + instance).get(0);
        Reply modified = IntegrationApp.admin.post("/v2/process-instances/" + instance + "/modification", Map.of(
                "activateInstructions", List.of(Map.of("elementId", toElementId)),
                "terminateInstructions", List.of(Map.of("elementInstanceKey", active.path("elementInstanceKey").asText()))));
        assertThat(modified.status()).as("modification " + fromElementId + " -> " + toElementId + ": " + modified.body()).isEqualTo(204);
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
