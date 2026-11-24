package com.lcs.star.bpa;

import static com.lcs.star.bpa.CamundaSteps.awaitOpenTask;
import static com.lcs.star.bpa.CamundaSteps.awaitSignOffTask;
import static com.lcs.star.bpa.CamundaSteps.completeSignOff;
import static com.lcs.star.bpa.CamundaSteps.elements;
import static com.lcs.star.bpa.CamundaSteps.json;
import static com.lcs.star.bpa.CamundaSteps.poll;
import static com.lcs.star.bpa.CamundaSteps.toJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcs.star.bpa.api.TestBrowser;
import com.lcs.star.bpa.persistence.WorkPackPrintRepository;
import com.lcs.star.bpa.worker.GenerateWorkPackPrintWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
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
 * BPA-10: the Workpack print, one test per Gherkin scenario. The Background goes the real way: create the Work Pack, complete Confirm
 * Workpack unchanged, the release check returns Ready, the Project Engineer approves, and the Production Planner schedules it
 * (planned start 03 Nov 2026, planned end 06 Nov 2026); ServiceTask_GenerateWorkPackPrint then runs for real. The app runs against the
 * integration Camunda 8.10.1 with its job workers on, as in ScheduleApiTest (BPA-11). Today is 21 Oct 2026 (Asia/Riyadh).
 *
 * <p>Test data: the seed has what the story names (cutting sheet CS-3310 on DWG-118, none on DWG-121), but DWG-121's latest revision is
 * IFI, so its Work Pack cannot pass the release check; that test arranges an IFC revision and a reservation (as BPA-11 did) and
 * {@link #restoreSeed()} puts the seed rows back afterwards.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(PrintApiTest.TestClockConfig.class)
class PrintApiTest {

    static final Instant TODAY = Instant.parse("2026-10-21T06:00:00Z"); // 09:00 in Riyadh
    static final String CREATE = "UserTask_CreateWorkPack";
    static final String CONFIRM = "UserTask_ConfirmWorkPack";
    static final String SCHEDULE = "UserTask_ScheduleWorkPack";
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
    GenerateWorkPackPrintWorker worker;

    @Autowired
    WorkPackPrintRepository prints;

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
    void BPA10_printIsGeneratedOnScheduling() throws IOException {
        // Given WP has Cutting 150 and Welding 250 man hours, drawing DWG-118, materials MAT-PIPE-06 (Cutting) and MAT-FLG-02 (Welding),
        // date to complete 06 Nov 2026, and cutting sheet CS-3310 is linked to DWG-118; When it is scheduled 03 to 06 Nov 2026
        Work wp = scheduled("DWG-118", List.of(phase("Cutting", 150), phase("Welding", 250)),
                List.of(material("MAT-PIPE-06", "Cutting"), material("MAT-FLG-02", "Welding")));
        awaitPrint(wp);

        HttpResponse<byte[]> response = loggedInAs("time-method-analyst").getBytes("/api/v1/workpacks/" + wp.number() + "/print");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/pdf");
        String text = pdfText(response.body());
        // Each value next to its label (a bare "contains" would pass with a wrong or missing row, because the date to complete and the
        // planned end are both 06 Nov 2026): added after review.
        assertThat(text).contains(wp.number());
        assertThat(text).containsPattern("Drawing\\s+DWG-118");
        assertThat(text).containsPattern("Date to complete\\s+06 Nov 2026");
        assertThat(text).containsPattern("Planned start\\s+03 Nov 2026");
        assertThat(text).containsPattern("Planned end\\s+06 Nov 2026");
        assertThat(text).containsPattern("Cutting\\s+150\\b");
        assertThat(text).containsPattern("Welding\\s+250\\b");
        assertThat(text).containsPattern("Total\\s+400\\b");
        assertThat(text).containsPattern("MAT-PIPE-06\\s+Cutting");
        assertThat(text).containsPattern("MAT-FLG-02\\s+Welding");
        assertThat(text).contains("CS-3310");
        Map<String, Object> row = jdbc.queryForMap("SELECT sha256, file, created_by FROM bpa.workpack_print WHERE workpack_id = ?", wp.id());
        assertThat(row.get("sha256")).as("the stored hash is the file's").isEqualTo(sha256((byte[]) row.get("file")));
        assertThat(sha256(response.body())).as("and the file served is the stored one").isEqualTo(row.get("sha256"));
        assertThat(row.get("created_by")).as("recorded as the Planner whose completion led to it (D63)").isEqualTo("production-planner");
    }

    @Test
    void BPA10_noPrintBeforeScheduling() {
        // Given the Work Pack is Released but not yet scheduled
        Work wp = atSchedule("DWG-118", List.of(phase("Cutting", 150)), List.of(material("MAT-PIPE-06", "Cutting")));

        HttpResponse<String> response = loggedInAs("time-method-analyst").get("/api/v1/workpacks/" + wp.number() + "/print");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(t -> assertThat(t).startsWith("application/problem+json"));
        assertThat(json(response.body()).path("detail").asText()).isEqualTo("Workpack print not available");
    }

    @Test
    void BPA10_decimalManHoursPrintAsEntered() throws IOException {
        Work wp = scheduled("DWG-118", List.of(phase("Cutting", new java.math.BigDecimal("150.5"))), List.of(material("MAT-PIPE-06", "Cutting")));
        awaitPrint(wp);

        String text = pdfText(loggedInAs("time-method-analyst").getBytes("/api/v1/workpacks/" + wp.number() + "/print").body());

        assertThat(text).contains("150.5").doesNotContain("150.50");
    }

    @Test
    void BPA10_printWithoutCuttingSheet() throws IOException {
        // Given no cutting sheet is linked to drawing DWG-121 (the seed has none; its revision and a reservation are arranged so the
        // Work Pack can pass the release check)
        approvableDrawing("DWG-121");
        Work wp = scheduled("DWG-121", List.of(phase("Cutting", 150)), List.of(material("MAT-PIPE-06", "Cutting")));
        awaitPrint(wp);

        HttpResponse<byte[]> response = loggedInAs("time-method-analyst").getBytes("/api/v1/workpacks/" + wp.number() + "/print");

        assertThat(response.statusCode()).as("the print is generated").isEqualTo(200);
        assertThat(pdfText(response.body())).contains("No cutting sheet available");
    }

    @Test
    void BPA10_rerunDoesNotCreateSecondPrint() {
        Work wp = scheduled("DWG-118", List.of(phase("Cutting", 150)), List.of(material("MAT-PIPE-06", "Cutting")));
        awaitPrint(wp);
        String sha = jdbc.queryForObject("SELECT sha256 FROM bpa.workpack_print WHERE workpack_id = ?", String.class, wp.id());
        int auditRows = printAuditRows(wp);
        JobClient client = Mockito.mock(JobClient.class, Mockito.RETURNS_DEEP_STUBS);

        // A retry after a lost completion (D29, D65, 09 S7), even a day later: nothing is generated again. The existing print is looked
        // for first, before anything else is read: even with the schedule gone (not a state the process produces; arranged so that a job
        // that went on to read it would fail) the job completes (added after review).
        jdbc.update("DELETE FROM bpa.workpack_schedule WHERE workpack_id = ?", wp.id());
        clock.set(TODAY.plus(Duration.ofDays(1)));
        worker.handle(client, job(101L, wp.instance(), Map.of("completedBy", "production-planner")));
        worker.handle(client, job(102L, wp.instance(), Map.of("completedBy", "production-planner")));

        Mockito.verify(client).newCompleteCommand(101L);
        Mockito.verify(client).newCompleteCommand(102L);
        assertThat(printRows(wp)).as("exactly one print for " + wp.number()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT sha256 FROM bpa.workpack_print WHERE workpack_id = ?", String.class, wp.id())).isEqualTo(sha);
        assertThat(printAuditRows(wp)).as("no audit row for a re-run").isEqualTo(auditRows);
    }

    @Test
    void BPA10_printOpensWorkPackForAssignment() {
        // Given the Work Pack has no print and its assignment_state is empty: it is Released and waits to be scheduled
        Work wp = atSchedule("DWG-118", List.of(phase("Cutting", 150)), List.of(material("MAT-PIPE-06", "Cutting")));
        assertThat(assignmentState(wp)).isNull();

        // When ServiceTask_GenerateWorkPackPrint completes (the Planner schedules it)
        schedule(wp);
        awaitPrint(wp);

        assertThat(poll(() -> assignmentState(wp), s -> s != null, "assignment_state of " + wp.number())).isEqualTo("AwaitingFile");
        // The audit row (D64, data model §6.1): the state was empty before (old value null), by the Planner whose completion led here.
        assertThat(jdbc.queryForList("""
                SELECT new_value || ' by ' || username FROM bpa.audit_log
                WHERE table_name = 'workpack' AND column_name = 'assignment_state' AND record_id = ? AND old_value IS NULL""",
                String.class, wp.id())).containsExactly("AwaitingFile by production-planner");
        // The process moves on and waits for the assignment file (BPA-12 builds that step).
        poll(() -> CamundaSteps.activeElements(wp.instance()).stream().map(e -> e.path("elementId").asText()).toList(),
                ids -> ids.contains("IntermediateCatchEvent_AssignmentFileReceived"), "the instance of " + wp.number() + " waiting for the assignment file");
    }

    @Test
    void BPA10_printIsServedInline() {
        Work wp = scheduled("DWG-118", List.of(phase("Cutting", 150)), List.of(material("MAT-PIPE-06", "Cutting")));
        awaitPrint(wp);

        HttpResponse<byte[]> response = loggedInAs("time-method-analyst").getBytes("/api/v1/workpacks/" + wp.number() + "/print");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/pdf");
        assertThat(response.headers().firstValue("Content-Disposition")).hasValue("inline; filename=\"" + wp.number() + ".pdf\"");
        assertThat(new String(response.body(), 0, 5, StandardCharsets.ISO_8859_1)).as("a PDF").isEqualTo("%PDF-");
    }

    @Test
    void BPA10_otherRolesCannotReadPrint() {
        Work wp = scheduled("DWG-118", List.of(phase("Cutting", 150)), List.of(material("MAT-PIPE-06", "Cutting")));
        awaitPrint(wp);

        HttpResponse<byte[]> response = loggedInAs("project-engineer").getBytes("/api/v1/workpacks/" + wp.number() + "/print");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(new String(response.body(), StandardCharsets.ISO_8859_1)).as("no PDF is returned").doesNotContain("%PDF");
    }

    // --- not scenarios ----------------------------------------------------------------------------------------------------

    /** The other two allowed groups read it, and an anonymous call is a 401 (the contract; groups ASSUMPTION in the story). */
    @Test
    void theAllowedGroupsReadThePrintAndAnonymousCannot() {
        Work wp = scheduled("DWG-118", List.of(phase("Cutting", 150)), List.of(material("MAT-PIPE-06", "Cutting")));
        awaitPrint(wp);
        String path = "/api/v1/workpacks/" + wp.number() + "/print";

        assertThat(loggedInAs("production-planner").getBytes(path).statusCode()).isEqualTo(200);
        assertThat(loggedInAs("production-team").getBytes(path).statusCode()).isEqualTo(200);
        assertThat(new TestBrowser(port).getBytes(path).statusCode()).isEqualTo(401);
    }

    /** An unknown Work Pack reuses "Work Pack not found"; a malformed number is the contract's InvalidWorkpackNumber. */
    @Test
    void unknownAndMalformedWorkPackNumbers() {
        TestBrowser analyst = loggedInAs("time-method-analyst");

        HttpResponse<String> unknown = analyst.get("/api/v1/workpacks/WP-999999/print");
        assertThat(unknown.statusCode()).as(unknown.body()).isEqualTo(404);
        assertThat(json(unknown.body()).path("detail").asText()).isEqualTo("Work Pack not found");
        HttpResponse<String> malformed = analyst.get("/api/v1/workpacks/WP-12/print");
        assertThat(malformed.statusCode()).as(malformed.body()).isEqualTo(400);
        assertThat(json(malformed.body()).path("detail").asText()).isEqualTo("Workpack number must match WP-######");
    }

    /**
     * The list the portal shows (a new operation of this story, x-assumption): the Work Packs that have a print, newest first, with
     * what a row shows. Not for the Project Engineer.
     */
    @Test
    void theListShowsPrintedWorkPacksNewestFirst() {
        Work older = scheduled("DWG-118", List.of(phase("Cutting", 150)), List.of(material("MAT-PIPE-06", "Cutting")));
        awaitPrint(older);
        clock.set(TODAY.plus(Duration.ofHours(2)));
        Work newer = scheduled("DWG-118", List.of(phase("Cutting", 150)), List.of(material("MAT-PIPE-06", "Cutting")));
        awaitPrint(newer);
        Work notScheduled = atSchedule("DWG-118", List.of(phase("Cutting", 150)), List.of(material("MAT-PIPE-06", "Cutting")));

        HttpResponse<String> response = loggedInAs("production-team").get("/api/v1/workpack-prints");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        List<JsonNode> rows = elements(json(response.body()));
        assertThat(rows).extracting(r -> r.path("workpackNumber").asText()).doesNotContain(notScheduled.number());
        List<String> numbers = rows.stream().map(r -> r.path("workpackNumber").asText()).toList();
        assertThat(numbers).contains(older.number(), newer.number());
        assertThat(numbers.indexOf(newer.number())).as("newest first").isLessThan(numbers.indexOf(older.number()));
        JsonNode row = rows.get(numbers.indexOf(newer.number()));
        assertThat(row.path("drawingRef").asText()).isEqualTo("DWG-118");
        assertThat(row.path("plannedStartDate").asText()).isEqualTo("2026-11-03");
        assertThat(row.path("plannedEndDate").asText()).isEqualTo("2026-11-06");
        assertThat(row.path("generatedAt").asText()).isNotBlank();
        assertThat(loggedInAs("project-engineer").get("/api/v1/workpack-prints").statusCode()).isEqualTo(403);
        assertThat(new TestBrowser(port).get("/api/v1/workpack-prints").statusCode()).isEqualTo(401);
    }

    /** A Work Pack that is not scheduled has nothing to print: a fault (retries, then an incident), not a business rejection. */
    @Test
    void workerFailsForAWorkPackThatIsNotScheduled() {
        Work wp = atSchedule("DWG-118", List.of(phase("Cutting", 150)), List.of(material("MAT-PIPE-06", "Cutting")));
        JobClient client = Mockito.mock(JobClient.class, Mockito.RETURNS_DEEP_STUBS);

        assertThatThrownBy(() -> worker.handle(client, job(111L, wp.instance(), Map.of("completedBy", "production-planner"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not scheduled");

        Mockito.verify(client, Mockito.never()).newCompleteCommand(Mockito.anyLong());
        assertThat(printRows(wp)).isZero();
        assertThat(assignmentState(wp)).isNull();
    }

    /** A HEAD request is served by the GET handler, so the group rule must hold for it too (added after review). */
    @Test
    void aHeadRequestFollowsTheSameGroupRule() {
        Work wp = scheduled("DWG-118", List.of(phase("Cutting", 150)), List.of(material("MAT-PIPE-06", "Cutting")));
        awaitPrint(wp);
        String path = "/api/v1/workpacks/" + wp.number() + "/print";

        assertThat(loggedInAs("project-engineer").head(path).statusCode()).isEqualTo(403);
        assertThat(loggedInAs("project-engineer").head("/api/v1/workpack-prints").statusCode()).isEqualTo(403);
        assertThat(loggedInAs("time-method-analyst").head(path).statusCode()).isEqualTo(200);
    }

    /** Only a Released Work Pack is printed (D25): arranged by hand, since the process never schedules one that is not Released. */
    @Test
    void workerFailsForAWorkPackThatIsNotReleased() {
        Work wp = atSchedule("DWG-118", List.of(phase("Cutting", 150)), List.of(material("MAT-PIPE-06", "Cutting")));
        jdbc.update("""
                INSERT INTO bpa.workpack_schedule (workpack_id, planned_start_date, planned_end_date, scheduled_by, created_by, updated_by)
                VALUES (?, DATE '2026-11-03', DATE '2026-11-06', 'test', 'test', 'test')""", wp.id());
        jdbc.update("UPDATE bpa.workpack SET status = 'Draft' WHERE id = ?", wp.id());
        JobClient client = Mockito.mock(JobClient.class, Mockito.RETURNS_DEEP_STUBS);

        assertThatThrownBy(() -> worker.handle(client, job(121L, wp.instance(), Map.of("completedBy", "production-planner"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not Released");

        Mockito.verify(client, Mockito.never()).newCompleteCommand(Mockito.anyLong());
        assertThat(printRows(wp)).isZero();
        assertThat(assignmentState(wp)).isNull();
    }

    /**
     * The print is stored once, and storing it opens the Work Pack for assignment files only while the state is still empty, so a Work
     * Pack that has moved on (Assigning) is never reset (D50, D61; added after review). Through the repository, because the worker's own
     * "already printed" check makes this path unreachable from a job.
     */
    @Test
    void aStateThatHasMovedOnIsNotResetAndASecondStoreChangesNothing() {
        Work wp = atSchedule("DWG-118", List.of(phase("Cutting", 150)), List.of(material("MAT-PIPE-06", "Cutting")));
        jdbc.update("UPDATE bpa.workpack SET assignment_state = 'Assigning' WHERE id = ?", wp.id());

        assertThat(prints.save(wp.id(), new byte[] {1, 2, 3}, "first", TODAY, "test")).isTrue();
        assertThat(prints.save(wp.id(), new byte[] {4, 5, 6}, "second", TODAY, "test")).as("a second store writes nothing").isFalse();

        assertThat(assignmentState(wp)).isEqualTo("Assigning");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM bpa.audit_log WHERE table_name = 'workpack' AND column_name = 'assignment_state' AND record_id = ?""",
                Integer.class, wp.id())).isZero();
        assertThat(printRows(wp)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT sha256 FROM bpa.workpack_print WHERE workpack_id = ?", String.class, wp.id())).isEqualTo("first");
    }

    // --- arranging the Given, and putting the seed back ---------------------------------------------------------------------

    record Seed(List<Map<String, Object>> revisions, List<Map<String, Object>> reservations) {
    }

    private void touch(String drawing) {
        touched.computeIfAbsent(drawing, d -> new Seed(
                jdbc.queryForList("SELECT * FROM bpa.drawing_revision WHERE drawing_ref = ?", d),
                jdbc.queryForList("SELECT * FROM bpa.material_reservation WHERE drawing_ref = ?", d)));
    }

    /** The drawing passes the release check for MAT-PIPE-06: an IFC revision and a reservation (the seed's DWG-121 is IFI). */
    private void approvableDrawing(String drawing) {
        touch(drawing);
        jdbc.update("DELETE FROM bpa.drawing_revision WHERE drawing_ref = ?", drawing);
        jdbc.update("DELETE FROM bpa.material_reservation WHERE drawing_ref = ?", drawing);
        jdbc.update("""
                INSERT INTO bpa.drawing_revision (drawing_ref, revision, status, registered_at, created_by, updated_by)
                VALUES (?, 'A', 'IFC', ?, 'test', 'test')""", drawing, Timestamp.from(TODAY.minus(Duration.ofDays(30))));
        jdbc.update("""
                INSERT INTO bpa.material_reservation (traceability_number, drawing_ref, material_ref, created_by, updated_by)
                VALUES (?, ?, 'MAT-PIPE-06', 'test', 'test')""", "TR-T" + UUID.randomUUID().toString().substring(0, 6), drawing);
    }

    private void insert(String table, Map<String, Object> row) {
        String columns = String.join(", ", row.keySet());
        String marks = String.join(", ", row.keySet().stream().map(c -> "?").toList());
        jdbc.update("INSERT INTO bpa." + table + " (" + columns + ") VALUES (" + marks + ")", row.values().toArray());
    }

    // --- the Background: a Work Pack that is Released, then scheduled ---------------------------------------------------------

    record Work(long instance, UUID id, String number, String scheduleTask) {
    }

    private static Map<String, Object> phase(String phase, Object manhours) {
        return Map.of("phase", phase, "manhours", manhours);
    }

    private static Map<String, Object> material(String materialRef, String phase) {
        return Map.of("materialRef", materialRef, "phase", phase);
    }

    /** Create, confirm unchanged, release check Ready, the Project Engineer approves: the process waits at Schedule Work Pack. */
    private Work atSchedule(String drawing, List<Map<String, Object>> phases, List<Map<String, Object>> materials) {
        TestBrowser analyst = loggedInAs("time-method-analyst");
        HttpResponse<String> started = AssignmentFlow.startWorkPack(analyst, jdbc);
        assertThat(started.statusCode()).as("POST /api/v1/workpacks: " + started.body()).isEqualTo(201);
        long instance = Long.parseLong(json(started.body()).path("processInstanceKey").asText());
        String create = awaitOpenTask(instance, CREATE).path("userTaskKey").asText();
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("activityRef", "A-2201");
        variables.put("drawingRef", drawing);
        variables.put("phases", phases);
        variables.put("materials", materials);
        variables.put("departmentRef", "Fabrication");
        variables.put("completionDate", "2026-11-06");
        HttpResponse<String> done = analyst.post("/api/v1/tasks/" + create + "/complete", toJson(Map.of("variables", variables)));
        assertThat(done.statusCode()).as("complete Create: " + done.body()).isEqualTo(204);
        String confirm = awaitOpenTask(instance, CONFIRM).path("userTaskKey").asText();
        UUID id = (UUID) jdbc.queryForMap("SELECT id FROM bpa.workpack WHERE process_instance_key = ?", instance).get("id");
        String number = jdbc.queryForObject("SELECT workpack_number FROM bpa.workpack WHERE id = ?", String.class, id);
        HttpResponse<String> open = analyst.get("/api/v1/tasks/" + confirm);
        Map<String, Object> taskVariables = JSON.convertValue(json(open.body()).path("variables"), new TypeReference<Map<String, Object>>() {
        });
        HttpResponse<String> confirmed = analyst.post("/api/v1/tasks/" + confirm + "/complete", toJson(Map.of("variables", taskVariables)));
        assertThat(confirmed.statusCode()).as("complete Confirm: " + confirmed.body()).isEqualTo(204);
        HttpResponse<String> approved = completeSignOff(loggedInAs("project-engineer"), awaitSignOffTask(instance), "Approved", null);
        assertThat(approved.statusCode()).as("approve: " + approved.body()).isEqualTo(204);
        String scheduleTask = awaitOpenTask(instance, SCHEDULE).path("userTaskKey").asText();
        return new Work(instance, id, number, scheduleTask);
    }

    /** The Production Planner schedules it 03 to 06 Nov 2026 (valid on 21 Oct: the earliest allowed date is 28 Oct). */
    private void schedule(Work wp) {
        HttpResponse<String> done = loggedInAs("production-planner").post("/api/v1/tasks/" + wp.scheduleTask() + "/complete",
                toJson(Map.of("variables", Map.of("plannedStartDate", "2026-11-03", "plannedEndDate", "2026-11-06"))));
        assertThat(done.statusCode()).as("complete Schedule: " + done.body()).isEqualTo(204);
        poll(() -> jdbc.queryForObject("SELECT count(*) FROM bpa.workpack_schedule WHERE workpack_id = ?", Integer.class, wp.id()),
                n -> n > 0, "the schedule of " + wp.number() + " saved");
    }

    private Work scheduled(String drawing, List<Map<String, Object>> phases, List<Map<String, Object>> materials) {
        Work wp = atSchedule(drawing, phases, materials);
        schedule(wp);
        return wp;
    }

    private void awaitPrint(Work wp) {
        poll(() -> printRows(wp), n -> n > 0, "the print of " + wp.number());
    }

    private int printRows(Work wp) {
        return jdbc.queryForObject("SELECT count(*) FROM bpa.workpack_print WHERE workpack_id = ?", Integer.class, wp.id());
    }

    /** The audit rows the print could add: its own columns and the Work Pack's assignment_state. */
    private int printAuditRows(Work wp) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM bpa.audit_log a
                WHERE (a.table_name = 'workpack_print' AND a.record_id IN (SELECT id FROM bpa.workpack_print WHERE workpack_id = ?))
                   OR (a.table_name = 'workpack' AND a.record_id = ? AND a.column_name = 'assignment_state')""",
                Integer.class, wp.id(), wp.id());
    }

    private String assignmentState(Work wp) {
        return jdbc.queryForObject("SELECT assignment_state FROM bpa.workpack WHERE id = ?", String.class, wp.id());
    }

    private static String pdfText(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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
        Mockito.when(job.getElementId()).thenReturn("ServiceTask_GenerateWorkPackPrint");
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
