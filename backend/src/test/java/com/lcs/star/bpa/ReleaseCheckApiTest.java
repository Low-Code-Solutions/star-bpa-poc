package com.lcs.star.bpa;

import static com.lcs.star.bpa.CamundaSteps.assertStaysEmpty;
import static com.lcs.star.bpa.CamundaSteps.awaitOpenTask;
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
import com.lcs.star.bpa.worker.EvaluateReleaseInputsWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
 * BPA-8: the release check, one test per Gherkin scenario (the outline's three rows inside its one test). The app runs against
 * the integration Camunda 8.10.1 with its job workers on, as in HourJournalHeaderApiTest and ConfirmWorkPackApiTest. Each test
 * goes the real way: create the Work Pack, complete Confirm Workpack unchanged (BPA-23 saves nothing and re-checks nothing for
 * an unchanged Draft), then ServiceTask_UpdateHourJournalHeader, ServiceTask_EvaluateReleaseInputs, the DMN and the gateway.
 *
 * <p>Test data (PO decision, 5 Nov 2026): the seed does not match the story's Givens (see the plan), so each test arranges
 * exactly its Given for the drawing it names, and {@link #restoreSeed()} puts the seed rows back afterwards.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(ReleaseCheckApiTest.TestClockConfig.class)
class ReleaseCheckApiTest {

    static final Instant TODAY = Instant.parse("2026-10-21T06:00:00Z"); // 09:00 in Riyadh
    static final String CREATE = "UserTask_CreateWorkPack";
    static final String CONFIRM = "UserTask_ConfirmWorkPack";
    static final String SIGN_OFF = "UserTask_SignOffRelease";
    static final String NOT_RESERVED = "Materials are not reserved for this Workpack";
    static final String NOT_APPROVED = "Drawing is not approved (IFC or As-Built required)";
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
    EvaluateReleaseInputsWorker worker;

    /** The seed rows of each drawing this test touched, kept the first time it is arranged. */
    private final Map<String, Seed> touched = new LinkedHashMap<>();

    @BeforeEach
    void today() {
        clock.set(TODAY);
    }

    @AfterEach
    void restoreSeed() {
        touched.forEach((drawing, seed) -> {
            jdbc.update("DELETE FROM bpa.drawing_revision WHERE drawing_ref = ?", drawing);
            jdbc.update("DELETE FROM bpa.material_reservation WHERE drawing_ref = ?", drawing);
            seed.revisions().forEach(row -> insert("drawing_revision", row));
            seed.reservations().forEach(row -> insert("material_reservation", row));
        });
        touched.clear();
    }

    // --- the scenarios ----------------------------------------------------------------------------------------------------

    @Test
    void BPA8_workPackMeetingReleaseConditionIsConfirmed() {
        arrange("DWG-118", List.of(rev("A", "IFC", 30)), List.of("MAT-PIPE-06"));
        Draft wp = draft("DWG-118", List.of(line("MAT-PIPE-06")));

        Outcome outcome = confirm(wp);

        assertThat(outcome.ready()).as("a sign-off task is created").isTrue();
        assertThat(releaseResult(wp.instance())).isEqualTo("Ready");
        assertThat(status(wp)).as(wp.number() + " stays Draft").isEqualTo("Draft");
        assertThat(signOffGroups(wp)).containsExactly("project-engineer");
        assertStaysEmpty(() -> openTasks(wp.instance(), CONFIRM), "Confirm Workpack task for " + wp.number());
    }

    @Test
    void BPA8_materialNotReservedBlocksConfirmation() {
        arrange("DWG-119", List.of(rev("A", "IFC", 30)), List.of());
        Draft wp = draft("DWG-119", List.of(line("MAT-PLATE-12")));

        Outcome outcome = confirm(wp);

        assertThat(outcome.ready()).isFalse();
        assertThat(outcome.message()).isEqualTo(NOT_RESERVED);
        assertThat(releaseResult(wp.instance())).isEqualTo("Not ready");
        assertStaysEmpty(() -> openTasks(wp.instance(), SIGN_OFF), "sign-off task for " + wp.number());
    }

    @Test
    void BPA8_drawingNotApprovedBlocksConfirmation() {
        arrange("DWG-121", List.of(rev("A", "IFR", 30)), List.of("MAT-PIPE-06"));
        Draft wp = draft("DWG-121", List.of(line("MAT-PIPE-06")));

        Outcome outcome = confirm(wp);

        assertThat(outcome.ready()).isFalse();
        assertThat(outcome.message()).isEqualTo(NOT_APPROVED);
        assertStaysEmpty(() -> openTasks(wp.instance(), SIGN_OFF), "sign-off task for " + wp.number());
    }

    @Test
    void BPA8_ifaAndIfiDrawingsAreNotApproved() {
        // Examples: | IFA | Not ready |, | IFI | Not ready |, | IFC | Ready |
        Map<String, String> examples = new LinkedHashMap<>();
        examples.put("IFA", "Not ready");
        examples.put("IFI", "Not ready");
        examples.put("IFC", "Ready");
        examples.forEach((status, result) -> {
            arrange("DWG-140", List.of(rev("A", status, 30)), List.of("MAT-PIPE-06"));
            Draft wp = draft("DWG-140", List.of(line("MAT-PIPE-06")));

            Outcome outcome = confirm(wp);

            assertThat(releaseResult(wp.instance())).as("status " + status).isEqualTo(result);
            assertThat(outcome.ready()).as("status " + status).isEqualTo("Ready".equals(result));
        });
    }

    @Test
    void BPA8_drawingWithoutRevisionsIsNotApproved() {
        arrange("DWG-141", List.of(), List.of("MAT-PIPE-06"));
        Draft wp = draft("DWG-141", List.of(line("MAT-PIPE-06")));

        Outcome outcome = confirm(wp);

        assertThat(outcome.ready()).isFalse();
        assertThat(outcome.message()).isEqualTo(NOT_APPROVED);

        // Added after review: a drawing whose only revisions are Superseded has no approved revision either (no rows left to
        // approve it), so it is also not approved.
        arrange("DWG-141", List.of(rev("A", "Superseded", 60)), List.of("MAT-PIPE-06"));
        Draft onlySuperseded = draft("DWG-141", List.of(line("MAT-PIPE-06")));

        Outcome second = confirm(onlySuperseded);

        assertThat(second.ready()).isFalse();
        assertThat(second.message()).isEqualTo(NOT_APPROVED);
    }

    @Test
    void BPA8_oneUnreservedMaterialBlocks() {
        arrange("DWG-130", List.of(rev("A", "IFC", 30)), List.of("MAT-PIPE-06"));
        Draft wp = draft("DWG-130", List.of(line("MAT-PIPE-06"), line("MAT-PLATE-12")));

        Outcome outcome = confirm(wp);

        assertThat(outcome.ready()).isFalse();
        assertThat(releaseResult(wp.instance())).isEqualTo("Not ready");
        assertThat(outcome.message()).isEqualTo(NOT_RESERVED);
    }

    @Test
    void BPA8_bothConditionsMissingReturnsFirstReason() {
        arrange("DWG-150", List.of(rev("A", "IFR", 30)), List.of());
        Draft wp = draft("DWG-150", List.of(line("MAT-PIPE-06")));

        Outcome outcome = confirm(wp);

        assertThat(outcome.ready()).isFalse();
        assertThat(outcome.message()).as("the first rule's reason, not the drawing's").isEqualTo(NOT_RESERVED);
    }

    @Test
    void BPA8_taskWaitsUntilMasterDataIsFixed() {
        arrange("DWG-119", List.of(rev("A", "IFC", 30)), List.of());
        Draft wp = draft("DWG-119", List.of(line("MAT-PLATE-12")));
        Outcome first = confirm(wp);
        assertThat(first.ready()).isFalse();
        assertThat(first.message()).isEqualTo(NOT_RESERVED);

        // "When MAT-PLATE-12 is reserved for DWG-119 and I complete the Confirm Workpack task again"
        reserve("DWG-119", "MAT-PLATE-12");
        Outcome second = confirm(wp.withTask(first.taskKey()));

        assertThat(second.ready()).isTrue();
        assertThat(releaseResult(wp.instance())).isEqualTo("Ready");
        assertThat(signOffGroups(wp)).containsExactly("project-engineer");
        // Added after review: the earlier reason is cleared once the check is Ready (BPMN maps releaseReason, null when Ready).
        poll(() -> variable(wp.instance(), "confirmMessage"), v -> v == null || "null".equals(v), "confirmMessage cleared for " + wp.number());
    }

    @Test
    void BPA8_asBuiltDrawingCountsAsApproved() {
        // Revision C, the newest row, is Superseded (taken from the D365 event); the latest revision that is not Superseded is B,
        // As-Built. A check that did not skip Superseded rows would look at C and block (added after review).
        arrange("DWG-130", List.of(rev("A", "Superseded", 60), rev("B", "As-Built", 30), rev("C", "Superseded", 10)), List.of("MAT-PIPE-06"));
        Draft wp = draft("DWG-130", List.of(line("MAT-PIPE-06")));

        Outcome outcome = confirm(wp);

        assertThat(outcome.ready()).isTrue();
        assertThat(releaseResult(wp.instance())).isEqualTo("Ready");
    }

    @Test
    void BPA8_checkUsesLatestRevision() {
        // B is Superseded, C (latest) is IFR. An older revision A still says IFC, so a check that looks at any revision, or
        // at the oldest, would wrongly pass.
        arrange("DWG-118", List.of(rev("A", "IFC", 90), rev("B", "Superseded", 60), rev("C", "IFR", 30)), List.of("MAT-PIPE-06"));
        Draft wp = draft("DWG-118", List.of(line("MAT-PIPE-06")));

        Outcome outcome = confirm(wp);

        assertThat(outcome.ready()).isFalse();
        assertThat(outcome.message()).isEqualTo(NOT_APPROVED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void BPA8_evaluatingInputsTwiceGivesSameResult() {
        arrange("DWG-118", List.of(rev("A", "IFC", 30)), List.of("MAT-PIPE-06"));
        Draft wp = draft("DWG-118", List.of(line("MAT-PIPE-06"))); // Confirm stays open: the token is not at the evaluate step
        JobClient client = Mockito.mock(JobClient.class, Mockito.RETURNS_DEEP_STUBS);
        String before = snapshotOfWorkPackData(wp);

        worker.handle(client, job(31L, wp.instance()));
        worker.handle(client, job(32L, wp.instance()));

        for (long key : new long[] {31L, 32L}) {
            ArgumentCaptor<Map<String, Object>> variables = ArgumentCaptor.forClass(Map.class);
            Mockito.verify(client.newCompleteCommand(key)).variables(variables.capture());
            assertThat(variables.getValue()).containsEntry("materialsReserved", true).containsEntry("drawingApproved", true);
        }
        assertThat(snapshotOfWorkPackData(wp)).as("no data is changed").isEqualTo(before);
    }

    // --- arranging the Given, and putting the seed back ---------------------------------------------------------------------

    record Rev(String revision, String status, int daysAgo) {
    }

    record Seed(List<Map<String, Object>> revisions, List<Map<String, Object>> reservations) {
    }

    private static Rev rev(String revision, String status, int daysAgo) {
        return new Rev(revision, status, daysAgo);
    }

    /** The drawing's revisions and the materials reserved for it are exactly these (the seed's own rows are replaced). */
    private void arrange(String drawing, List<Rev> revisions, List<String> reservedMaterials) {
        touched.computeIfAbsent(drawing, d -> new Seed(
                jdbc.queryForList("SELECT * FROM bpa.drawing_revision WHERE drawing_ref = ?", d),
                jdbc.queryForList("SELECT * FROM bpa.material_reservation WHERE drawing_ref = ?", d)));
        jdbc.update("DELETE FROM bpa.drawing_revision WHERE drawing_ref = ?", drawing);
        jdbc.update("DELETE FROM bpa.material_reservation WHERE drawing_ref = ?", drawing);
        for (Rev r : revisions) {
            jdbc.update("""
                    INSERT INTO bpa.drawing_revision (drawing_ref, revision, status, registered_at, created_by, updated_by)
                    VALUES (?, ?, ?, ?, 'test', 'test')""", drawing, r.revision(), r.status(),
                    Timestamp.from(TODAY.minus(Duration.ofDays(r.daysAgo()))));
        }
        reservedMaterials.forEach(material -> reserve(drawing, material));
    }

    private void reserve(String drawing, String material) {
        jdbc.update("""
                INSERT INTO bpa.material_reservation (traceability_number, drawing_ref, material_ref, created_by, updated_by)
                VALUES (?, ?, ?, 'test', 'test')""", "TR-T" + UUID.randomUUID().toString().substring(0, 6), drawing, material);
    }

    private void insert(String table, Map<String, Object> row) {
        String columns = String.join(", ", row.keySet());
        String marks = String.join(", ", row.keySet().stream().map(c -> "?").toList());
        jdbc.update("INSERT INTO bpa." + table + " (" + columns + ") VALUES (" + marks + ")", row.values().toArray());
    }

    // --- the portal's steps -----------------------------------------------------------------------------------------------

    record Draft(long instance, UUID id, String number, String task) {
        Draft withTask(String other) {
            return new Draft(instance, id, number, other);
        }
    }

    /** What happened after Confirm was completed: a sign-off task, or the Confirm task back with a reason. */
    record Outcome(boolean ready, String taskKey, String message) {
    }

    private record Line(String materialRef) {
    }

    private static Line line(String materialRef) {
        return new Line(materialRef);
    }

    /** The Background: a Draft Work Pack for the drawing and materials, its Confirm task open. */
    private Draft draft(String drawing, List<Line> materials) {
        TestBrowser analyst = loggedInAs("time-method-analyst");
        HttpResponse<String> started = AssignmentFlow.startWorkPack(analyst, jdbc);
        assertThat(started.statusCode()).as("POST /api/v1/workpacks: " + started.body()).isEqualTo(201);
        long instance = Long.parseLong(json(started.body()).path("processInstanceKey").asText());
        String create = awaitOpenTask(instance, CREATE).path("userTaskKey").asText();
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("activityRef", "A-2201");
        variables.put("drawingRef", drawing);
        variables.put("phases", List.of(Map.of("phase", "Cutting", "manhours", 150), Map.of("phase", "Welding", "manhours", 250)));
        variables.put("materials", materials.stream().map(m -> Map.of("materialRef", m.materialRef(), "phase", "Cutting")).toList());
        variables.put("departmentRef", "Fabrication");
        variables.put("completionDate", "2026-11-06");
        HttpResponse<String> done = analyst.post("/api/v1/tasks/" + create + "/complete", toJson(Map.of("variables", variables)));
        assertThat(done.statusCode()).as("complete Create: " + done.body()).isEqualTo(204);
        String confirm = awaitOpenTask(instance, CONFIRM).path("userTaskKey").asText();
        Map<String, Object> row = jdbc.queryForMap("SELECT id, workpack_number FROM bpa.workpack WHERE process_instance_key = ?", instance);
        return new Draft(instance, (UUID) row.get("id"), (String) row.get("workpack_number"), confirm);
    }

    /** "When I complete the Confirm Workpack task": unchanged, then wait for the sign-off task or the returned Confirm task. */
    private Outcome confirm(Draft wp) {
        TestBrowser analyst = loggedInAs("time-method-analyst");
        HttpResponse<String> open = analyst.get("/api/v1/tasks/" + wp.task());
        assertThat(open.statusCode()).as("GET task: " + open.body()).isEqualTo(200);
        Map<String, Object> variables = JSON.convertValue(json(open.body()).path("variables"), new TypeReference<Map<String, Object>>() {
        });
        HttpResponse<String> done = analyst.post("/api/v1/tasks/" + wp.task() + "/complete", toJson(Map.of("variables", variables)));
        assertThat(done.statusCode()).as("complete Confirm: " + done.body()).isEqualTo(204);
        return poll(() -> {
            if (!openTasks(wp.instance(), SIGN_OFF).isEmpty()) {
                return new Outcome(true, null, null);
            }
            List<JsonNode> back = openTasks(wp.instance(), CONFIRM).stream()
                    .filter(t -> !wp.task().equals(t.path("userTaskKey").asText())).toList();
            if (back.isEmpty()) {
                return null;
            }
            String key = back.get(0).path("userTaskKey").asText();
            JsonNode task = json(analyst.get("/api/v1/tasks/" + key).body());
            return new Outcome(false, key, task.path("confirmMessage").asText(null));
        }, o -> o != null, "the release check of " + wp.number());
    }

    private List<String> signOffGroups(Draft wp) {
        List<String> groups = new ArrayList<>();
        openTasks(wp.instance(), SIGN_OFF).forEach(t -> t.path("candidateGroups").forEach(g -> groups.add(g.asText())));
        return groups;
    }

    private String releaseResult(long instance) {
        String value = poll(() -> variable(instance, "releaseResult"), v -> v != null, "releaseResult of " + instance);
        return value.replace("\"", "");
    }

    private String status(Draft wp) {
        return jdbc.queryForObject("SELECT status FROM bpa.workpack WHERE id = ?", String.class, wp.id());
    }

    /** Everything the check could change, as text: the Work Pack, its lines, its Header and the audit rows of those (not the whole log: other Work Packs write theirs meanwhile). */
    private String snapshotOfWorkPackData(Draft wp) {
        return jdbc.queryForList("SELECT * FROM bpa.workpack WHERE id = ?", wp.id())
                + "|" + jdbc.queryForList("SELECT * FROM bpa.workpack_phase WHERE workpack_id = ? ORDER BY phase", wp.id())
                + "|" + jdbc.queryForList("SELECT * FROM bpa.workpack_material WHERE workpack_id = ? ORDER BY id", wp.id())
                + "|" + jdbc.queryForList("SELECT * FROM bpa.hour_journal_header WHERE workpack_id = ?", wp.id())
                + "|" + jdbc.queryForObject("""
                SELECT count(*) FROM bpa.audit_log WHERE record_id = ?
                   OR record_id IN (SELECT id FROM bpa.workpack_phase WHERE workpack_id = ?)
                   OR record_id IN (SELECT id FROM bpa.workpack_material WHERE workpack_id = ?)
                   OR record_id IN (SELECT id FROM bpa.hour_journal_header WHERE workpack_id = ?)""", Integer.class, wp.id(), wp.id(), wp.id(), wp.id());
    }

    private TestBrowser loggedInAs(String username) {
        TestBrowser browser = new TestBrowser(port);
        HttpResponse<String> login = browser.login(username, PersonaSyncTest.DEMO_PASSWORD);
        assertThat(login.statusCode()).as("login " + username + ": " + login.body()).isEqualTo(200);
        return browser;
    }

    private static ActivatedJob job(long key, long instance) {
        ActivatedJob job = Mockito.mock(ActivatedJob.class);
        Mockito.when(job.getKey()).thenReturn(key);
        Mockito.when(job.getProcessInstanceKey()).thenReturn(instance);
        Mockito.when(job.getElementId()).thenReturn("ServiceTask_EvaluateReleaseInputs");
        Mockito.when(job.getVariablesAsMap()).thenReturn(Map.of("completedBy", "time-method-analyst"));
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
