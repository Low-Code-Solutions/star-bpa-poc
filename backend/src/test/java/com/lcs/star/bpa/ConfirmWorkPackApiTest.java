package com.lcs.star.bpa;

import static com.lcs.star.bpa.CamundaSteps.assertStaysEmpty;
import static com.lcs.star.bpa.CamundaSteps.awaitOpenTask;
import static com.lcs.star.bpa.CamundaSteps.completed;
import static com.lcs.star.bpa.CamundaSteps.elements;
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
import com.lcs.star.bpa.domain.WorkPackInput;
import com.lcs.star.bpa.persistence.WorkPackRepository;
import com.lcs.star.bpa.worker.SaveWorkPackWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.util.ArrayList;
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
 * BPA-23: correcting a Draft Work Pack in the Confirm Workpack task, one test per Gherkin scenario (the outline's rows inside
 * its one test). The app runs against the integration Camunda 8.10.1 with its job workers on, as WorkPackApiTest (BPA-6).
 * Background: today is 21 Oct 2026 (Asia/Riyadh); a Draft Work Pack with Cutting 150, Welding 250 and MAT-PIPE-06 in Cutting,
 * created through the real flow, with its Confirm Workpack task open.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(WorkPackApiTest.TestClockConfig.class)
class ConfirmWorkPackApiTest {

    static final String CREATE = "UserTask_CreateWorkPack";
    static final String CONFIRM = "UserTask_ConfirmWorkPack";
    static final String SAVE_CORRECTIONS = "ServiceTask_SaveWorkPackCorrections";
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
    WorkPackApiTest.TestClock clock;

    @Autowired
    SaveWorkPackWorker saveWorkPack;

    @Autowired
    WorkPackRepository workPacks;

    @BeforeEach
    void today() {
        clock.set(WorkPackApiTest.TODAY);
    }

    @Test
    void BPA23_correctManHoursRecalculatesHeader() {
        Draft wp = draftWithConfirmOpen();

        Outcome outcome = complete(wp, withManhours(wp.variables(), "Welding", 300));

        assertThat(outcome.saved()).as(outcome.toString()).isTrue();
        assertThat(manhours(wp, "Welding")).isEqualByComparingTo("300");
        JsonNode header = poll(() -> headers(wp.number()), h -> h.size() == 1
                && h.get(0).path("estimatedManhours").decimalValue().compareTo(new BigDecimal("450")) == 0, "header 450").get(0);
        assertThat(header.path("estimatedManhours").decimalValue()).isEqualByComparingTo("450");
    }

    @Test
    void BPA23_correctMaterialsAndPhases() {
        Draft wp = draftWithConfirmOpen();
        UUID pipeLine = jdbc.queryForObject("SELECT id FROM bpa.workpack_material WHERE workpack_id = ? AND material_ref = 'MAT-PIPE-06'",
                UUID.class, wp.id());

        Outcome outcome = complete(wp, withMaterial(wp.variables(), "MAT-PLATE-12", "Cutting"));

        assertThat(outcome.saved()).as(outcome.toString()).isTrue();
        assertThat(materials(wp)).containsExactly("MAT-PIPE-06 Cutting", "MAT-PLATE-12 Cutting");
        assertThat(jdbc.queryForObject("SELECT id FROM bpa.workpack_material WHERE workpack_id = ? AND material_ref = 'MAT-PIPE-06'",
                UUID.class, wp.id())).as("updated in place, not re-inserted (F13)").isEqualTo(pipeLine);
    }

    @Test
    void BPA23_invalidCorrectionIsRejected() {
        Draft wp = draftWithConfirmOpen();
        Map<String, String> rows = new LinkedHashMap<>(); // change -> message
        rows.put("Cutting 0", "Man hours must be greater than 0");
        rows.put("MAT-FLG-02 in Finishing", "Phase Finishing is not selected for this Work Pack");
        rows.put("MAT-PIPE-06 in Cutting again", "Material MAT-PIPE-06 is listed twice for phase Cutting");

        Draft current = wp;
        for (Map.Entry<String, String> row : rows.entrySet()) {
            Map<String, Object> change = switch (row.getKey()) {
                case "Cutting 0" -> withManhours(wp.variables(), "Cutting", 0);
                case "MAT-FLG-02 in Finishing" -> withMaterial(wp.variables(), "MAT-FLG-02", "Finishing");
                default -> withMaterial(wp.variables(), "MAT-PIPE-06", "Cutting");
            };
            Outcome outcome = complete(current, change);

            assertThat(outcome.saved()).as(row.getKey() + " rejected").isFalse();
            assertThat(outcome.validationMessages()).as(row.getKey()).containsExactly(row.getValue());
            assertThat(manhours(wp, "Cutting")).as("still Cutting 150").isEqualByComparingTo("150");
            assertThat(materials(wp)).as("no material in Finishing").noneMatch(m -> m.endsWith("Finishing"));
            current = new Draft(wp.instance(), wp.id(), wp.number(), outcome.returnedTask(), wp.variables());
        }
    }

    @Test
    void BPA23_validationMessagesClearedAfterSave() {
        Draft wp = draftWithConfirmOpen();
        Outcome rejected = complete(wp, withMaterial(wp.variables(), "MAT-FLG-02", "Finishing"));
        assertThat(rejected.validationMessages()).containsExactly("Phase Finishing is not selected for this Work Pack");

        Draft returned = new Draft(wp.instance(), wp.id(), wp.number(), rejected.returnedTask(), taskVariables(rejected.returnedTask()));
        Outcome saved = complete(returned, withMaterial(wp.variables(), "MAT-FLG-02", "Welding"));

        assertThat(saved.saved()).as(saved.toString()).isTrue();
        assertThat(materials(wp)).contains("MAT-FLG-02 Welding");
        assertThat(variable(wp.instance(), "validationMessages")).as("cleared by the save step").isEqualTo("[]");
    }

    @Test
    void BPA23_otherRolesCannotCorrect() {
        Draft wp = draftWithConfirmOpen();
        TestBrowser planner = loggedInAs("production-planner");

        HttpResponse<String> tasks = planner.get("/api/v1/tasks");
        assertThat(tasks.statusCode()).isEqualTo(200);
        assertThat(elements(json(tasks.body()))).noneMatch(t -> wp.task().equals(t.path("key").asText()));

        HttpResponse<String> complete = planner.post("/api/v1/tasks/" + wp.task() + "/complete", toJson(Map.of("variables", wp.variables())));
        assertThat(complete.statusCode()).isEqualTo(403);
    }

    @Test
    void BPA23_correctionIsAudited() {
        Draft wp = draftWithConfirmOpen();

        Outcome outcome = complete(wp, withManhours(wp.variables(), "Welding", 300));

        assertThat(outcome.saved()).isTrue();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.old_value, a.new_value, a.username, a.changed_at FROM bpa.audit_log a
                JOIN bpa.workpack_phase p ON p.id = a.record_id
                WHERE a.table_name = 'workpack_phase' AND a.column_name = 'manhours' AND a.old_value IS NOT NULL
                  AND p.workpack_id = ? AND p.phase = 'Welding'""", wp.id());
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.get("old_value")).isEqualTo("250");
            assertThat(r.get("new_value")).isEqualTo("300");
            assertThat(r.get("username")).isEqualTo("time-method-analyst");
            assertThat(((Timestamp) r.get("changed_at")).toInstant()).isEqualTo(WorkPackApiTest.TODAY);
        });
    }

    @Test
    void BPA23_noCorrectionAfterRelease() {
        Draft wp = draftWithConfirmOpen();
        assertThat(complete(wp, withManhours(wp.variables(), "Welding", 300)).saved()).isTrue();
        poll(() -> headers(wp.number()), h -> h.size() == 1
                && h.get(0).path("estimatedManhours").decimalValue().compareTo(new BigDecimal("450")) == 0, "header 450");

        // The real sign-off (BPA-9 re-check; until BPA-9 this was a stand-in that set Released and locked the Header in the
        // database and moved the token past Confirm). The correction ran the release check, which is Ready (DWG-118 has an IFC
        // revision and a reservation for MAT-PIPE-06; D1, D10), so the Project Engineer approves Sign off release through the
        // task API and record-release-signoff sets Released and locks the Header (D8). The assertions below are unchanged.
        String signOff = CamundaSteps.awaitSignOffTask(wp.instance());
        HttpResponse<String> approved = CamundaSteps.completeSignOff(loggedInAs("project-engineer"), signOff, "Approved", null);
        assertThat(approved.statusCode()).as("approve: " + approved.body()).isEqualTo(204);
        poll(() -> headers(wp.number()).get(0).path("locked").asBoolean(false), locked -> locked,
                "Header of " + wp.number() + " locked by the sign-off");
        assertThat(jdbc.queryForObject("SELECT status FROM bpa.workpack WHERE id = ?", String.class, wp.id())).isEqualTo("Released");

        assertStaysEmpty(() -> openTasks(wp.instance(), CONFIRM), "Confirm Workpack task for " + wp.number());
        assertThat(headers(wp.number()).get(0).path("locked").asBoolean(false)).as("man hours locked").isTrue();

        // BPA-23's own part (Ahsan, 4 Nov): a corrections job for a Work Pack that is no longer Draft is an incident (retries 0)
        // and saves nothing. The sign-off above is now real (BPA-9); this is the code BPA-23 adds for D8.
        JobClient client = Mockito.mock(JobClient.class, Mockito.RETURNS_DEEP_STUBS);
        saveWorkPack.handle(client, job(31L, wp.instance(), SAVE_CORRECTIONS, withManhours(wp.variables(), "Welding", 999)));
        Mockito.verify(client.newFailCommand(31L)).retries(0);
        Mockito.verify(client.newFailCommand(31L).retries(0)).errorMessage(wp.number() + " is Released; corrections are closed (D8)");
        assertThat(manhours(wp, "Welding")).as("nothing saved").isEqualByComparingTo("300");
    }

    /**
     * Not a scenario (BPA-23 review): every branch of the in-place correction (F13, A4) and its exact audit set (D64, §6.1).
     * Workpack columns change; a phase is added and one removed; a line moves phase by id; a line is removed; a new line is
     * added. Then an id-less copy of the saved lines changes nothing, and an id-less line listed before an id line cannot take
     * that line's row.
     */
    @Test
    void multiChangeCorrectionIsAppliedInPlace() {
        long instance = 4503599627370511L;
        var saved = workPacks.save(instance, new WorkPackInput("A-2201", "DWG-118", "Fabrication", java.time.LocalDate.parse("2026-11-06"),
                List.of(new WorkPackInput.Phase("Cutting", new BigDecimal("150")), new WorkPackInput.Phase("Welding", new BigDecimal("250"))),
                List.of(new WorkPackInput.Material("MAT-PIPE-06", "Cutting"), new WorkPackInput.Material("MAT-FLG-02", "Welding"))),
                "time-method-analyst");
        UUID wp = saved.id();
        String pipe = lineId(wp, "MAT-PIPE-06");
        java.time.Instant later = WorkPackApiTest.TODAY.plusSeconds(3600);
        clock.set(later);

        workPacks.correct(wp, new WorkPackInput("A-2201", "DWG-119", "Painting", java.time.LocalDate.parse("2026-11-20"),
                List.of(new WorkPackInput.Phase("Cutting", new BigDecimal("150")), new WorkPackInput.Phase("Engineering", new BigDecimal("10"))),
                List.of(new WorkPackInput.Material("MAT-PIPE-06", "Engineering", pipe), new WorkPackInput.Material("MAT-PLATE-12", "Cutting"))),
                "time-method-analyst");

        assertThat(jdbc.queryForList("SELECT phase || ' ' || manhours FROM bpa.workpack_phase WHERE workpack_id = ? ORDER BY phase", String.class, wp))
                .containsExactly("Cutting 150.00", "Engineering 10.00");
        assertThat(jdbc.queryForList("SELECT material_ref || ' ' || phase FROM bpa.workpack_material WHERE workpack_id = ? ORDER BY 1", String.class, wp))
                .containsExactly("MAT-PIPE-06 Engineering", "MAT-PLATE-12 Cutting");
        assertThat(lineId(wp, "MAT-PIPE-06")).as("moved in place, same id").isEqualTo(pipe);
        String id = wp.toString();
        assertThat(auditSince(wp, later)).containsExactlyInAnyOrder(
                "workpack.drawing_ref DWG-118->DWG-119", "workpack.department_ref Fabrication->Painting",
                "workpack.completion_date 2026-11-06->2026-11-20",
                "workpack_phase.workpack_id null->" + id, "workpack_phase.phase null->Engineering", "workpack_phase.manhours null->10",
                "workpack_phase.workpack_id " + id + "->null", "workpack_phase.phase Welding->null", "workpack_phase.manhours 250->null",
                "workpack_material.phase Cutting->Engineering",
                "workpack_material.workpack_id " + id + "->null", "workpack_material.material_ref MAT-FLG-02->null", "workpack_material.phase Welding->null",
                "workpack_material.workpack_id null->" + id, "workpack_material.material_ref null->MAT-PLATE-12", "workpack_material.phase null->Cutting");

        // An id-less copy of what is saved: matched by material and phase, nothing changes.
        int rows = auditSince(wp, later).size();
        workPacks.correct(wp, new WorkPackInput("A-2201", "DWG-119", "Painting", java.time.LocalDate.parse("2026-11-20"),
                List.of(new WorkPackInput.Phase("Cutting", new BigDecimal("150")), new WorkPackInput.Phase("Engineering", new BigDecimal("10"))),
                List.of(new WorkPackInput.Material("MAT-PIPE-06", "Engineering"), new WorkPackInput.Material("MAT-PLATE-12", "Cutting"))),
                "time-method-analyst");
        assertThat(auditSince(wp, later)).as("no change, no audit row").hasSize(rows);
        assertThat(lineId(wp, "MAT-PIPE-06")).isEqualTo(pipe);

        // An id-less line listed first cannot take the row an id line claims (two passes): it is a new line.
        workPacks.correct(wp, new WorkPackInput("A-2201", "DWG-119", "Painting", java.time.LocalDate.parse("2026-11-20"),
                List.of(new WorkPackInput.Phase("Cutting", new BigDecimal("150")), new WorkPackInput.Phase("Engineering", new BigDecimal("10"))),
                List.of(new WorkPackInput.Material("MAT-PIPE-06", "Engineering"), new WorkPackInput.Material("MAT-PIPE-06", "Cutting", pipe),
                        new WorkPackInput.Material("MAT-PLATE-12", "Cutting"))),
                "time-method-analyst");
        assertThat(jdbc.queryForList("SELECT material_ref || ' ' || phase FROM bpa.workpack_material WHERE workpack_id = ? ORDER BY 1", String.class, wp))
                .containsExactly("MAT-PIPE-06 Cutting", "MAT-PIPE-06 Engineering", "MAT-PLATE-12 Cutting");
        assertThat(jdbc.queryForObject("SELECT phase FROM bpa.workpack_material WHERE id = ?::uuid", String.class, pipe)).isEqualTo("Cutting");
    }

    /**
     * Not a scenario (BPA-23 review; rule by Ahsan, 5 Nov): a retried corrections job whose first run committed completes
     * without re-checking, even after Riyadh midnight has made its date to complete "past" (D20), and adds no audit row.
     */
    @Test
    void correctionRetryAfterMidnightCompletesWithoutRechecking() {
        long instance = 4503599627370512L;
        var saved = workPacks.save(instance, new WorkPackInput("A-2201", "DWG-118", "Fabrication", java.time.LocalDate.parse("2026-10-21"),
                List.of(new WorkPackInput.Phase("Cutting", new BigDecimal("150")), new WorkPackInput.Phase("Welding", new BigDecimal("250"))),
                List.of(new WorkPackInput.Material("MAT-PIPE-06", "Cutting"))), "time-method-analyst");
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("activityRef", "A-2201");
        variables.put("drawingRef", "DWG-118");
        variables.put("departmentRef", "Fabrication");
        variables.put("completionDate", "2026-10-21"); // today in Riyadh at 21 Oct 06:00 UTC
        variables.put("phases", List.of(Map.of("phase", "Cutting", "manhours", 150), Map.of("phase", "Welding", "manhours", 300)));
        variables.put("materials", List.of(Map.of("id", lineId(saved.id(), "MAT-PIPE-06"), "materialRef", "MAT-PIPE-06", "phase", "Cutting")));
        JobClient client = Mockito.mock(JobClient.class, Mockito.RETURNS_DEEP_STUBS);

        saveWorkPack.handle(client, job(41L, instance, SAVE_CORRECTIONS, variables));
        int rows = auditSince(saved.id(), WorkPackApiTest.TODAY.minusSeconds(1)).size();
        clock.set(java.time.Instant.parse("2026-10-21T21:30:00Z")); // 22 Oct 00:30 in Riyadh: 21 Oct is now past
        saveWorkPack.handle(client, job(42L, instance, SAVE_CORRECTIONS, variables));

        Mockito.verify(client, Mockito.never()).newThrowErrorCommand(Mockito.anyLong());
        Mockito.verify(client).newCompleteCommand(41L);
        Mockito.verify(client).newCompleteCommand(42L);
        assertThat(jdbc.queryForObject("SELECT manhours FROM bpa.workpack_phase WHERE workpack_id = ? AND phase = 'Welding'", BigDecimal.class,
                saved.id())).isEqualByComparingTo("300");
        assertThat(auditSince(saved.id(), WorkPackApiTest.TODAY.minusSeconds(1))).as("the retry adds no audit row").hasSize(rows);
    }

    private String lineId(UUID workpackId, String materialRef) {
        return jdbc.queryForObject("SELECT id::text FROM bpa.workpack_material WHERE workpack_id = ? AND material_ref = ? ORDER BY created_at LIMIT 1",
                String.class, workpackId, materialRef);
    }

    /** Audit rows written from this instant on, for the Work Pack and its phase and material rows, as "table.column old->new". */
    private List<String> auditSince(UUID workpackId, java.time.Instant from) {
        return jdbc.queryForList("""
                SELECT a.table_name || '.' || a.column_name || ' ' || coalesce(a.old_value, 'null') || '->' || coalesce(a.new_value, 'null')
                FROM bpa.audit_log a
                WHERE a.changed_at >= ? AND a.table_name IN ('workpack', 'workpack_phase', 'workpack_material')
                  AND (a.record_id = ?
                       -- child rows, deleted ones included: linked by their audited workpack_id
                       OR a.record_id IN (SELECT w.record_id FROM bpa.audit_log w
                                          WHERE w.column_name = 'workpack_id' AND (w.old_value = ? OR w.new_value = ?)))""",
                String.class, Timestamp.from(from), workpackId, workpackId.toString(), workpackId.toString());
    }

    // --- the portal's steps ---------------------------------------------------------------------------------------------

    record Draft(long instance, UUID id, String number, String task, Map<String, Object> variables) {
    }

    record Outcome(boolean saved, String returnedTask, List<String> validationMessages) {
    }

    /** The Background: a Draft Work Pack (Cutting 150, Welding 250, MAT-PIPE-06 in Cutting) with its Confirm task open. */
    private Draft draftWithConfirmOpen() {
        TestBrowser analyst = loggedInAs("time-method-analyst");
        HttpResponse<String> started = AssignmentFlow.startWorkPack(analyst, jdbc);
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
        Map<String, Object> row = jdbc.queryForMap("SELECT id, workpack_number FROM bpa.workpack WHERE process_instance_key = ?", instance);
        return new Draft(instance, (UUID) row.get("id"), (String) row.get("workpack_number"), confirm, taskVariables(confirm));
    }

    /** What the Confirm screen gets: the task's read-allow-listed variables (BPA-26), which carry the material line ids. */
    private Map<String, Object> taskVariables(String task) {
        HttpResponse<String> response = loggedInAs("time-method-analyst").get("/api/v1/tasks/" + task);
        assertThat(response.statusCode()).as("GET task: " + response.body()).isEqualTo(200);
        return JSON.convertValue(json(response.body()).path("variables"), new TypeReference<Map<String, Object>>() {
        });
    }

    /** Completes the Confirm task; then either the corrections save completes, or the task comes back with messages. */
    private Outcome complete(Draft wp, Map<String, Object> variables) {
        TestBrowser analyst = loggedInAs("time-method-analyst");
        int savesBefore = elements(IntegrationApp.admin.post("/v2/element-instances/search", Map.of("filter", Map.of(
                "processInstanceKey", Long.toString(wp.instance()), "elementId", SAVE_CORRECTIONS, "state", "COMPLETED"))).body().path("items")).size();
        HttpResponse<String> done = analyst.post("/api/v1/tasks/" + wp.task() + "/complete", toJson(Map.of("variables", variables)));
        assertThat(done.statusCode()).as("complete Confirm: " + done.body()).isEqualTo(204);
        return poll(() -> {
            if (completed(wp.instance(), SAVE_CORRECTIONS, savesBefore + 1)) {
                return new Outcome(true, null, List.of());
            }
            List<JsonNode> back = openTasks(wp.instance(), CONFIRM).stream()
                    .filter(t -> !wp.task().equals(t.path("userTaskKey").asText())).toList();
            if (back.isEmpty()) {
                return null;
            }
            String key = back.get(0).path("userTaskKey").asText();
            List<String> messages = new ArrayList<>();
            json(analyst.get("/api/v1/tasks/" + key).body()).path("validationMessages").forEach(m -> messages.add(m.asText()));
            return new Outcome(false, key, messages);
        }, o -> o != null, "corrections of " + wp.number() + " saved or returned");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> withManhours(Map<String, Object> variables, String phase, Object manhours) {
        Map<String, Object> copy = copy(variables);
        List<Map<String, Object>> phases = new ArrayList<>();
        for (Object p : (List<Object>) copy.get("phases")) {
            Map<String, Object> line = new LinkedHashMap<>((Map<String, Object>) p);
            if (phase.equals(line.get("phase"))) {
                line.put("manhours", manhours);
            }
            phases.add(line);
        }
        copy.put("phases", phases);
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> withMaterial(Map<String, Object> variables, String materialRef, String phase) {
        Map<String, Object> copy = copy(variables);
        List<Object> materials = new ArrayList<>((List<Object>) copy.get("materials"));
        materials.add(Map.of("materialRef", materialRef, "phase", phase)); // a new line: no id
        copy.put("materials", materials);
        return copy;
    }

    private static Map<String, Object> copy(Map<String, Object> variables) {
        return JSON.convertValue(variables, new TypeReference<Map<String, Object>>() {
        });
    }

    private BigDecimal manhours(Draft wp, String phase) {
        return jdbc.queryForObject("SELECT manhours FROM bpa.workpack_phase WHERE workpack_id = ? AND phase = ?", BigDecimal.class, wp.id(), phase);
    }

    private List<String> materials(Draft wp) {
        return jdbc.queryForList("SELECT material_ref || ' ' || phase FROM bpa.workpack_material WHERE workpack_id = ? ORDER BY 1",
                String.class, wp.id());
    }

    private List<JsonNode> headers(String workpackNumber) {
        HttpResponse<String> response = loggedInAs("ppc-team").get("/api/v1/hour-journal-headers?workpackNumber=" + workpackNumber);
        assertThat(response.statusCode()).as("GET header: " + response.body()).isEqualTo(200);
        return elements(json(response.body()));
    }

    private TestBrowser loggedInAs(String username) {
        TestBrowser browser = new TestBrowser(port);
        HttpResponse<String> login = browser.login(username, PersonaSyncTest.DEMO_PASSWORD);
        assertThat(login.statusCode()).as("login " + username + ": " + login.body()).isEqualTo(200);
        return browser;
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
}
