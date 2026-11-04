package com.lcs.star.bpa;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcs.star.bpa.api.TestBrowser;
import com.lcs.star.bpa.domain.WorkPackInput;
import com.lcs.star.bpa.persistence.WorkPackRepository;
import com.lcs.star.bpa.persistence.WorkPackRepository.SavedWorkPack;
import com.lcs.star.bpa.worker.SaveWorkPackWorker;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.assertj.core.api.SoftAssertions;
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
 * BPA-6: creating a Work Pack, one test per Gherkin scenario (outlines run their rows inside the one test). The app runs
 * against the integration Camunda 8.10.1 with its job workers on, as {@code bpa-service}; users log in for real. "Today" comes
 * from a settable clock (S9; Background: 21 Oct 2026, Asia/Riyadh). The Background's master data is the R__ seed.
 * The context is closed after the class so its save-workpack worker cannot take jobs from other test classes.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(WorkPackApiTest.TestClockConfig.class)
class WorkPackApiTest {

    static final Instant TODAY = Instant.parse("2026-10-21T06:00:00Z"); // 09:00 in Riyadh
    static final String CREATE = "UserTask_CreateWorkPack";
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
    SaveWorkPackWorker saveWorkPack;

    @BeforeEach
    void today() {
        clock.set(TODAY);
    }

    @Test
    void BPA6_workPackIsCreatedWithEstimates() {
        Outcome outcome = submit(analyst(), validWorkPack());

        assertThat(outcome.saved()).as(outcome.toString()).isTrue();
        assertThat(outcome.workpackNumber()).matches("WP-[0-9]{6}");
        Map<String, Object> row = jdbc.queryForMap("SELECT status, created_by, id FROM bpa.workpack WHERE workpack_number = ?",
                outcome.workpackNumber());
        assertThat(row.get("status")).isEqualTo("Draft");
        assertThat(row.get("created_by")).as("D63: the Analyst who completed the task").isEqualTo("time-method-analyst");
        BigDecimal total = jdbc.queryForObject("SELECT sum(manhours) FROM bpa.workpack_phase WHERE workpack_id = ?",
                BigDecimal.class, row.get("id"));
        assertThat(total).as("Total Activity hours").isEqualByComparingTo("400");
        assertThat(jdbc.queryForList("SELECT material_ref || ' ' || phase FROM bpa.workpack_material WHERE workpack_id = ? ORDER BY 1",
                String.class, row.get("id"))).containsExactly("MAT-FLG-02 Welding", "MAT-PIPE-06 Cutting");
    }

    @Test
    void BPA6_contextPanelShowsActivityReadOnly() {
        JsonNode activity = activity(analyst(), "A-2201");

        assertThat(activity.path("projectRef").asText()).isEqualTo("PRJ-139");
        assertThat(activity.path("jobCodeRef").asText()).isEqualTo("JC-139");
        assertThat(activity.path("wbsRef").asText()).isEqualTo("11031");
        assertThat(activity.path("activityName").asText()).isNotBlank();
        assertThat(activity.path("facCrpo").asText()).isEqualTo("139");
        assertThat(activity.path("facType").asText()).isEqualTo("Module");
        assertThat(activity.path("facName").asText()).isEqualTo("M-01");
        assertThat(activity.path("discipline").asText()).isEqualTo("Piping");
        assertThat(activity.path("baselineStart").asText()).isEqualTo("2026-11-01");
        assertThat(activity.path("baselineEnd").asText()).isEqualTo("2026-11-30");
        assertThat(activity.path("budgetedUnits").decimalValue()).isEqualByComparingTo("1200");
    }

    @Test
    void BPA6_activityWithoutPlanDataCanBeUsed() {
        TestBrowser analyst = analyst();
        JsonNode activity = activity(analyst, "A-2299");
        assertThat(activity.has("baselineStart")).as("blank, so absent").isFalse();
        assertThat(activity.has("baselineEnd")).isFalse();
        assertThat(activity.has("budgetedUnits")).isFalse();

        Outcome outcome = submit(analyst, with(validWorkPack(), "activityRef", "A-2299"));

        assertThat(outcome.saved()).as(outcome.toString()).isTrue();
    }

    @Test
    void BPA6_requiredFields() {
        TestBrowser analyst = analyst();
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        rows.put("Project activity is required", without(validWorkPack(), "activityRef"));
        rows.put("Drawing is required", without(validWorkPack(), "drawingRef"));
        rows.put("At least one material is required", with(validWorkPack(), "materials", List.of()));
        rows.put("Select a phase for each material", with(validWorkPack(), "materials",
                List.of(Map.of("materialRef", "MAT-PIPE-06"), material("MAT-FLG-02", "Welding"))));
        rows.put("Assign Manhour to each phase", with(validWorkPack(), "phases",
                List.of(Map.of("phase", "Cutting"), phase("Welding", 250))));
        rows.put("Department is required", without(validWorkPack(), "departmentRef"));
        rows.put("Date to complete is required", without(validWorkPack(), "completionDate"));

        SoftAssertions rowsCheck = new SoftAssertions();
        rows.forEach((message, variables) -> {
            Outcome outcome = submit(analyst, variables);
            rowsCheck.assertThat(outcome.saved()).as("not saved: " + message).isFalse();
            rowsCheck.assertThat(outcome.validationMessages()).as("row '" + message + "'").containsExactly(message);
        });
        rowsCheck.assertAll();
    }

    @Test
    void BPA6_materialPhaseMustBeWorkPackPhase() {
        Outcome outcome = submit(analyst(), with(validWorkPack(), "materials",
                List.of(material("MAT-PIPE-06", "Cutting"), material("MAT-FLG-02", "Finishing"))));

        assertThat(outcome.saved()).isFalse();
        assertThat(outcome.validationMessages()).containsExactly("Phase Finishing is not selected for this Work Pack");
    }

    @Test
    void BPA6_duplicatePhaseIsRejected() {
        Outcome outcome = submit(analyst(), with(with(validWorkPack(), "phases", List.of(phase("Cutting", 150), phase("Cutting", 50))),
                "materials", List.of(material("MAT-PIPE-06", "Cutting"))));

        assertThat(outcome.saved()).isFalse();
        assertThat(outcome.validationMessages()).containsExactly("Phase Cutting is listed twice");
    }

    @Test
    void BPA6_duplicateMaterialInOnePhaseIsRejected() {
        TestBrowser analyst = analyst();

        Outcome sameSame = submit(analyst, with(validWorkPack(), "materials",
                List.of(material("MAT-PIPE-06", "Cutting"), material("MAT-PIPE-06", "Cutting"))));
        Outcome cuttingWelding = submit(analyst, with(validWorkPack(), "materials",
                List.of(material("MAT-PIPE-06", "Cutting"), material("MAT-PIPE-06", "Welding"))));

        assertThat(sameSame.saved()).as("Cutting | Cutting").isFalse();
        assertThat(sameSame.validationMessages()).containsExactly("Material MAT-PIPE-06 is listed twice for phase Cutting");
        assertThat(cuttingWelding.saved()).as("Cutting | Welding: " + cuttingWelding).isTrue();
    }

    @Test
    void BPA6_dateToCompleteBoundary() {
        TestBrowser analyst = analyst();
        SoftAssertions rowsCheck = new SoftAssertions();

        clock.set(Instant.parse("2026-10-21T06:00:00Z"));
        Outcome past = submit(analyst, with(validWorkPack(), "completionDate", "2026-10-20"));
        rowsCheck.assertThat(past.saved()).as("20 Oct at 21 Oct 06:00 UTC").isFalse();
        rowsCheck.assertThat(past.validationMessages()).containsExactly("Date to complete cannot be in the past");

        Outcome today = submit(analyst, with(validWorkPack(), "completionDate", "2026-10-21"));
        rowsCheck.assertThat(today.saved()).as("21 Oct at 21 Oct 06:00 UTC: " + today).isTrue();

        clock.set(Instant.parse("2026-10-20T21:30:00Z")); // 21 Oct 2026 00:30 in Riyadh
        Outcome riyadhMidnight = submit(analyst, with(validWorkPack(), "completionDate", "2026-10-21"));
        rowsCheck.assertThat(riyadhMidnight.saved()).as("21 Oct at 20 Oct 21:30 UTC: " + riyadhMidnight).isTrue();

        // Added after review (said in the PR, L15): at the same instant 20 Oct is already past in Riyadh, though not in UTC.
        // Without this row a UTC implementation would pass the three AC rows.
        Outcome utcToday = submit(analyst, with(validWorkPack(), "completionDate", "2026-10-20"));
        rowsCheck.assertThat(utcToday.saved()).as("20 Oct at 20 Oct 21:30 UTC").isFalse();
        rowsCheck.assertThat(utcToday.validationMessages()).containsExactly("Date to complete cannot be in the past");

        rowsCheck.assertAll();
    }

    @Test
    void BPA6_manHoursBoundary() {
        TestBrowser analyst = analyst();
        Map<String, String> rows = new LinkedHashMap<>(); // hours -> message, or "" when accepted
        rows.put("0", "Man hours must be greater than 0");
        rows.put("0.01", "");
        rows.put("0.001", "Man hours can have at most 2 decimal places");
        rows.put("99999.99", "");
        rows.put("100000", "Man hours cannot exceed 99,999.99");

        SoftAssertions rowsCheck = new SoftAssertions();
        rows.forEach((hours, message) -> {
            Outcome outcome = submit(analyst, with(validWorkPack(), "phases",
                    List.of(Map.of("phase", "Cutting", "manhours", new BigDecimal(hours)), phase("Welding", 250))));
            if (message.isEmpty()) {
                rowsCheck.assertThat(outcome.saved()).as(hours + " accepted: " + outcome).isTrue();
            } else {
                rowsCheck.assertThat(outcome.saved()).as(hours + " rejected").isFalse();
                rowsCheck.assertThat(outcome.validationMessages()).as(hours).containsExactly(message);
            }
        });
        rowsCheck.assertAll();
    }

    @Test
    void BPA6_rerunDoesNotCreateSecondWorkPack() {
        // The save-workpack job for one instance runs twice through the worker, as a retry after a lost completion would.
        // The AC's key 2251799813685501 is illustrative. A key from partition 2's range is used instead, because the
        // single-partition test Camunda numbers its own instances from 2251799813685249 and could reach the AC's value.
        long instance = 4503599627370497L;
        Map<String, Object> firstRun = with(validWorkPack(), "completionDate", "2026-10-21");
        JobClient client = Mockito.mock(JobClient.class, Mockito.RETURNS_DEEP_STUBS);

        saveWorkPack.handle(client, job(1L, instance, "ServiceTask_SaveWorkPack", firstRun));
        SavedWorkPack first = workPacks.find(instance).orElseThrow(() -> new AssertionError("first run saved nothing"));
        // The retry carries other data, and by now 21 Oct is in the past: neither may change or reject the saved Work Pack.
        clock.set(Instant.parse("2026-10-21T21:30:00Z")); // 22 Oct 00:30 in Riyadh
        saveWorkPack.handle(client, job(2L, instance, "ServiceTask_SaveWorkPack",
                with(validWorkPack(), "phases", List.of(phase("Cutting", 999), phase("Welding", 1)))));

        Mockito.verify(client, Mockito.never()).newThrowErrorCommand(Mockito.anyLong());
        Mockito.verify(client).newCompleteCommand(1L);
        Mockito.verify(client).newCompleteCommand(2L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.workpack WHERE process_instance_key = ?", Integer.class, instance))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT workpack_number FROM bpa.workpack WHERE process_instance_key = ?", String.class, instance))
                .isEqualTo(first.workpackNumber());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.workpack_phase WHERE workpack_id = ?", Integer.class, first.id()))
                .as("estimates kept, not doubled").isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT sum(manhours) FROM bpa.workpack_phase WHERE workpack_id = ?", BigDecimal.class, first.id()))
                .as("the first run's 150 + 250, not the retry's").isEqualByComparingTo("400");
    }

    @Test
    void BPA6_otherRolesCannotCreateWorkPack() {
        // An open Create Work Pack task exists, so "lists no Create Work Pack task for me" is about the group, not an empty list.
        TestBrowser analyst = analyst();
        HttpResponse<String> started = analyst.post("/api/v1/workpacks", "");
        assertThat(started.statusCode()).isEqualTo(201);
        String instance = json(started.body()).path("processInstanceKey").asText();
        String openTask = createTask(analyst, instance, null);
        TestBrowser productionTeam = loggedInAs("production-team");

        HttpResponse<String> start = productionTeam.post("/api/v1/workpacks", "");

        assertThat(start.statusCode()).isEqualTo(403);
        HttpResponse<String> tasks = productionTeam.get("/api/v1/tasks");
        assertThat(tasks.statusCode()).isEqualTo(200);
        assertThat(elements(json(tasks.body()))).noneMatch(t -> CREATE.equals(t.path("elementId").asText()))
                .noneMatch(t -> openTask.equals(t.path("key").asText()));
    }

    // --- decisions and data-model rules without a scenario of their own (BPA-6 review) -----------------------------------

    /** Ahsan, 4 Nov: until BPA-23, a save-workpack job from the corrections step is an incident and saves nothing. */
    @Test
    void correctionsJobRaisesAnIncident() {
        JobClient client = Mockito.mock(JobClient.class, Mockito.RETURNS_DEEP_STUBS);

        saveWorkPack.handle(client, job(3L, 4503599627370498L, "ServiceTask_SaveWorkPackCorrections", validWorkPack()));

        Mockito.verify(client.newFailCommand(3L)).retries(0);
        Mockito.verify(client.newFailCommand(3L).retries(0)).errorMessage("Save corrections is built by BPA-23");
        assertThat(workPacks.find(4503599627370498L)).isEmpty();
    }

    /** D63: completedBy is set by the task API from the session; a client that sends it is refused. */
    @Test
    void completedByCannotBeSentByTheClient() {
        TestBrowser analyst = analyst();
        HttpResponse<String> started = analyst.post("/api/v1/workpacks", "");
        String task = createTask(analyst, json(started.body()).path("processInstanceKey").asText(), null);

        HttpResponse<String> response = analyst.post("/api/v1/tasks/" + task + "/complete",
                toJson(Map.of("variables", with(validWorkPack(), "completedBy", "project-engineer"))));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response.body()).path("detail").asText()).isEqualTo("Variable completedBy is not allowed for this task");
    }

    /** D64, data model §6.1: one audit row per business column, old value null, canonical value, the user and the clock. */
    @Test
    void newWorkPackIsAudited() {
        long instance = 4503599627370499L;
        clock.set(TODAY);
        SavedWorkPack saved = workPacks.save(instance, new WorkPackInput("A-2201", "DWG-118", "Fabrication", LocalDate.parse("2026-11-06"),
                List.of(new WorkPackInput.Phase("Cutting", new BigDecimal("150.00"))), List.of(new WorkPackInput.Material("MAT-PIPE-06", "Cutting"))),
                "time-method-analyst");

        List<Map<String, Object>> workpack = jdbc.queryForList(
                "SELECT column_name, old_value, new_value, username, changed_at FROM bpa.audit_log WHERE record_id = ? ORDER BY column_name", saved.id());
        assertThat(workpack).extracting(r -> r.get("column_name")).containsExactly("activity_ref", "completion_date", "department_ref",
                "drawing_ref", "process_instance_key", "status", "workpack_number");
        assertThat(workpack).allSatisfy(r -> {
            assertThat(r.get("old_value")).isNull();
            assertThat(r.get("username")).isEqualTo("time-method-analyst");
            assertThat(((java.sql.Timestamp) r.get("changed_at")).toInstant()).isEqualTo(TODAY);
        });
        assertThat(workpack).filteredOn(r -> "completion_date".equals(r.get("column_name"))).singleElement()
                .satisfies(r -> assertThat(r.get("new_value")).isEqualTo("2026-11-06"));
        assertThat(jdbc.queryForObject("""
                SELECT a.new_value FROM bpa.audit_log a JOIN bpa.workpack_phase p ON p.id = a.record_id
                WHERE p.workpack_id = ? AND a.column_name = 'manhours'""", String.class, saved.id())).as("150.00 -> 150").isEqualTo("150");
        assertThat(jdbc.queryForObject("SELECT created_at FROM bpa.workpack WHERE id = ?", java.sql.Timestamp.class, saved.id()).toInstant())
                .as("audit columns from the same clock (S9)").isEqualTo(TODAY);
    }

    private static ActivatedJob job(long key, long instance, String elementId, Map<String, Object> variables) {
        ActivatedJob job = Mockito.mock(ActivatedJob.class);
        Map<String, Object> withUser = new LinkedHashMap<>(variables);
        withUser.put("completedBy", "time-method-analyst");
        Mockito.when(job.getKey()).thenReturn(key);
        Mockito.when(job.getProcessInstanceKey()).thenReturn(instance);
        Mockito.when(job.getElementId()).thenReturn(elementId);
        Mockito.when(job.getVariablesAsMap()).thenReturn(withUser);
        return job;
    }

    // --- the portal's steps, as React calls them ---------------------------------------------------------------------------

    /** Starts a Work Pack request, completes its Create Work Pack task with these variables and waits for the save step. */
    private Outcome submit(TestBrowser analyst, Map<String, Object> variables) {
        HttpResponse<String> started = analyst.post("/api/v1/workpacks", "");
        assertThat(started.statusCode()).as("POST /api/v1/workpacks: " + started.body()).isEqualTo(201);
        String instance = json(started.body()).path("processInstanceKey").asText();
        String task = createTask(analyst, instance, null);

        HttpResponse<String> completed = analyst.post("/api/v1/tasks/" + task + "/complete", toJson(Map.of("variables", variables)));
        assertThat(completed.statusCode()).as("complete: " + completed.body()).isEqualTo(204);

        // Saved: a workpack row for the instance. Rejected: the Create task comes back (new key) with validationMessages.
        long until = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < until) {
            List<String> numbers = jdbc.queryForList("SELECT workpack_number FROM bpa.workpack WHERE process_instance_key = ?",
                    String.class, Long.parseLong(instance));
            if (!numbers.isEmpty()) {
                return new Outcome(true, numbers.get(0), List.of());
            }
            String back = findCreateTask(analyst, instance, task);
            if (back != null) {
                JsonNode returned = json(analyst.get("/api/v1/tasks/" + back).body());
                List<String> messages = new ArrayList<>();
                returned.path("validationMessages").forEach(m -> messages.add(m.asText()));
                return new Outcome(false, null, messages);
            }
            pause();
        }
        throw new AssertionError("instance " + instance + ": neither saved nor returned within 60 s");
    }

    private String createTask(TestBrowser analyst, String instance, String notKey) {
        long until = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < until) {
            String key = findCreateTask(analyst, instance, notKey);
            if (key != null) {
                return key;
            }
            pause();
        }
        throw new AssertionError("no Create Work Pack task for instance " + instance);
    }

    private String findCreateTask(TestBrowser analyst, String instance, String notKey) {
        return elements(json(analyst.get("/api/v1/tasks").body())).stream()
                .filter(t -> CREATE.equals(t.path("elementId").asText()) && instance.equals(t.path("processInstanceKey").asText()))
                .map(t -> t.path("key").asText())
                .filter(key -> !key.equals(notKey))
                .findFirst().orElse(null);
    }

    private JsonNode activity(TestBrowser browser, String activityRef) {
        HttpResponse<String> response = browser.get("/api/v1/activities");
        assertThat(response.statusCode()).as("GET /api/v1/activities: " + response.body()).isEqualTo(200);
        return elements(json(response.body())).stream().filter(a -> activityRef.equals(a.path("activityRef").asText()))
                .findFirst().orElseThrow(() -> new AssertionError(activityRef + " not listed: " + response.body()));
    }

    private TestBrowser analyst() {
        return loggedInAs("time-method-analyst");
    }

    private TestBrowser loggedInAs(String username) {
        TestBrowser browser = new TestBrowser(port);
        HttpResponse<String> login = browser.login(username, PersonaSyncTest.DEMO_PASSWORD);
        assertThat(login.statusCode()).as("login " + username + ": " + login.body()).isEqualTo(200);
        return browser;
    }

    /** The Scenario "Work Pack is created with estimates" data: A-2201, DWG-118, Cutting 150 / Welding 250, Fabrication, 06 Nov. */
    private static Map<String, Object> validWorkPack() {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("activityRef", "A-2201");
        variables.put("drawingRef", "DWG-118");
        variables.put("phases", List.of(phase("Cutting", 150), phase("Welding", 250)));
        variables.put("materials", List.of(material("MAT-PIPE-06", "Cutting"), material("MAT-FLG-02", "Welding")));
        variables.put("departmentRef", "Fabrication");
        variables.put("completionDate", "2026-11-06");
        return variables;
    }

    private static Map<String, Object> phase(String phase, int manhours) {
        return Map.of("phase", phase, "manhours", manhours);
    }

    private static Map<String, Object> material(String materialRef, String phase) {
        return Map.of("materialRef", materialRef, "phase", phase);
    }

    private static Map<String, Object> with(Map<String, Object> variables, String name, Object value) {
        variables.put(name, value);
        return variables;
    }

    private static Map<String, Object> without(Map<String, Object> variables, String name) {
        variables.remove(name);
        return variables;
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

    private static void pause() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    record Outcome(boolean saved, String workpackNumber, List<String> validationMessages) {
    }

    /** A clock the scenarios set (S9; ADR 0013): "Given the system clock is …". */
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
