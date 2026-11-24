package com.lcs.star.bpa;

import static com.lcs.star.bpa.CamundaSteps.activeElements;
import static com.lcs.star.bpa.CamundaSteps.awaitOpenTask;
import static com.lcs.star.bpa.CamundaSteps.awaitSignOffTask;
import static com.lcs.star.bpa.CamundaSteps.completeSignOff;
import static com.lcs.star.bpa.CamundaSteps.completed;
import static com.lcs.star.bpa.CamundaSteps.elements;
import static com.lcs.star.bpa.CamundaSteps.json;
import static com.lcs.star.bpa.CamundaSteps.poll;
import static com.lcs.star.bpa.CamundaSteps.toJson;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcs.star.bpa.api.TestBrowser;
import com.lcs.star.bpa.config.PersonaFile;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The shared set-up of the assignment tests (BPA-12 AssignmentFileApiTest, BPA-22 WorkerAssignmentApiTest): taking a Work Pack the real
 * way, through the task API as React does, to Draft, Released or AwaitingFile (scheduled and printed, its instance waiting at the catch
 * event), uploading an assignment file as a user, and reading what that left behind. Today is 30 Oct 2026 (Asia/Riyadh) in both classes.
 */
public final class AssignmentFlow {

    public static final String CREATE = "UserTask_CreateWorkPack";
    public static final String CONFIRM = "UserTask_ConfirmWorkPack";
    public static final String SCHEDULE = "UserTask_ScheduleWorkPack";
    public static final String CATCH = "IntermediateCatchEvent_AssignmentFileReceived";
    public static final String END = "EndEvent_WorkPackAssigned";
    /** The user task of Process_PPC_TimesheetToProgress that the Epic 1 end event now starts (BPA-49); test classes cancel instances waiting here (shared Camunda). */
    public static final String BOOK_HOURS = "UserTask_BookHours";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final int port;
    private final JdbcTemplate jdbc;

    public AssignmentFlow(int port, JdbcTemplate jdbc) {
        this.port = port;
        this.jdbc = jdbc;
    }

    public record Work(long instance, UUID id, String number, String confirmTask, String scheduleTask) {
    }

    public record Upload(int status, JsonNode body) {
        public String id() {
            return body.path("id").asText();
        }
    }

    /** BPA-78: the project the tests start Work Packs for; every Work Pack of the seed and the fixtures is on a PRJ-139 activity (A-2201, A-2299, A-2301). */
    public static final String PROJECT = "PRJ-139";

    /**
     * BPA-78: time-method-analyst may start a Work Pack only for a project assigned to it, so a test class's own database needs the analyst's app_user row (the
     * persona sync of IntegrationApp writes to the shared Camunda's database, not to this one) and a user_project row. Idempotent: a reset in between does no harm.
     */
    public static void assignAnalystProject(JdbcTemplate jdbc, String projectRef) {
        for (PersonaFile.Persona persona : PersonaFile.load(new ClassPathResource("personas.yaml")).users()) {
            if ("time-method-analyst".equals(persona.username())) {
                jdbc.update("""
                        INSERT INTO bpa.app_user (username, employee_ref, email, created_by, updated_by)
                        VALUES (?, ?, ?, 'test', 'test') ON CONFLICT (username) DO NOTHING""", persona.username(), persona.employeeRef(), persona.email());
            }
        }
        jdbc.update("INSERT INTO bpa.user_project (username, project_ref, created_by, updated_by) VALUES ('time-method-analyst', ?, 'test', 'test') ON CONFLICT (username, project_ref) DO NOTHING",
                projectRef);
    }

    /** Starts a Work Pack request for {@link #PROJECT} as the analyst (BPA-78: the start carries the project, and the analyst has it assigned). */
    public static HttpResponse<String> startWorkPack(TestBrowser analyst, JdbcTemplate jdbc) {
        assignAnalystProject(jdbc, PROJECT);
        return analyst.post("/api/v1/workpacks", "{\"projectRef\":\"" + PROJECT + "\"}");
    }

    // --- Work Packs in the state a scenario needs ---------------------------------------------------------------------------

    public Work draft() {
        return draft("A-2201");
    }

    /** A Work Pack for this activity (A-2201 has a CBS code; A-2299 has none, BPA-15), at the Confirm task. */
    public Work draft(String activityRef) {
        TestBrowser analyst = loggedInAs("time-method-analyst");
        HttpResponse<String> started = startWorkPack(analyst, jdbc);
        assertThat(started.statusCode()).as("POST /api/v1/workpacks: " + started.body()).isEqualTo(201);
        long instance = Long.parseLong(json(started.body()).path("processInstanceKey").asText());
        String create = awaitOpenTask(instance, CREATE).path("userTaskKey").asText();
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("activityRef", activityRef);
        variables.put("drawingRef", "DWG-118");
        variables.put("phases", List.of(Map.of("phase", "Cutting", "manhours", 150)));
        variables.put("materials", List.of(Map.of("materialRef", "MAT-PIPE-06", "phase", "Cutting")));
        variables.put("departmentRef", "Fabrication");
        variables.put("completionDate", "2026-11-30");
        HttpResponse<String> done = analyst.post("/api/v1/tasks/" + create + "/complete", toJson(Map.of("variables", variables)));
        assertThat(done.statusCode()).as("complete Create: " + done.body()).isEqualTo(204);
        String confirm = awaitOpenTask(instance, CONFIRM).path("userTaskKey").asText();
        UUID id = (UUID) jdbc.queryForMap("SELECT id FROM bpa.workpack WHERE process_instance_key = ?", instance).get("id");
        String number = jdbc.queryForObject("SELECT workpack_number FROM bpa.workpack WHERE id = ?", String.class, id);
        return new Work(instance, id, number, confirm, null);
    }

    /** Confirmed unchanged, the release check Ready, approved: Released, waiting to be scheduled. */
    public Work released() {
        return released("A-2201");
    }

    public Work released(String activityRef) {
        Work draft = draft(activityRef);
        TestBrowser analyst = loggedInAs("time-method-analyst");
        HttpResponse<String> open = analyst.get("/api/v1/tasks/" + draft.confirmTask());
        Map<String, Object> variables = JSON.convertValue(json(open.body()).path("variables"), new TypeReference<Map<String, Object>>() {
        });
        HttpResponse<String> confirmed = analyst.post("/api/v1/tasks/" + draft.confirmTask() + "/complete", toJson(Map.of("variables", variables)));
        assertThat(confirmed.statusCode()).as("complete Confirm: " + confirmed.body()).isEqualTo(204);
        HttpResponse<String> approved = completeSignOff(loggedInAs("project-engineer"), awaitSignOffTask(draft.instance()), "Approved", null);
        assertThat(approved.statusCode()).as("approve: " + approved.body()).isEqualTo(204);
        String scheduleTask = awaitOpenTask(draft.instance(), SCHEDULE).path("userTaskKey").asText();
        return new Work(draft.instance(), draft.id(), draft.number(), draft.confirmTask(), scheduleTask);
    }

    /** The Planner schedules it (10 to 12 Nov, valid on 30 Oct: the earliest is 6 Nov); the print is generated and the instance waits for the file. */
    public void schedule(Work wp) {
        HttpResponse<String> done = loggedInAs("production-planner").post("/api/v1/tasks/" + wp.scheduleTask() + "/complete",
                toJson(Map.of("variables", Map.of("plannedStartDate", "2026-11-10", "plannedEndDate", "2026-11-12"))));
        assertThat(done.statusCode()).as("complete Schedule: " + done.body()).isEqualTo(204);
        poll(() -> activeIds(wp), ids -> ids.contains(CATCH), "the instance of " + wp.number() + " waiting for the assignment file");
    }

    /** Scheduled and printed: assignment_state AwaitingFile, the instance waiting at the catch event. */
    public Work awaiting() {
        return awaiting("A-2201");
    }

    public Work awaiting(String activityRef) {
        Work wp = released(activityRef);
        schedule(wp);
        assertThat(state(wp)).isEqualTo("AwaitingFile");
        return wp;
    }

    /** Waits until the instance has run to EndEvent_WorkPackAssigned (BPA-15), the end of Process_PPC_WorkPackAssignment (an end event completes at most once per instance). */
    public void awaitEnd(Work wp) {
        poll(() -> completed(wp.instance(), END, 1), done -> done, "the instance of " + wp.number() + " at " + END);
    }

    // --- uploads and what they leave behind ----------------------------------------------------------------------------------

    public Upload upload(String user, String fileName, byte[] file) {
        HttpResponse<String> response = loggedInAs(user).postFile("/api/v1/assignment-files", fileName, file);
        return new Upload(response.statusCode(), response.body() == null || response.body().isBlank() ? JSON.createObjectNode() : json(response.body()));
    }

    /** The result of an upload as the portal reads it (GET /api/v1/assignment-files/{id}/result), as production-team. */
    public JsonNode result(Upload upload) {
        HttpResponse<String> response = loggedInAs("production-team").get("/api/v1/assignment-files/" + upload.id() + "/result");
        assertThat(response.statusCode()).as("result: " + response.body()).isEqualTo(200);
        return json(response.body());
    }

    public static List<JsonNode> rows(Upload upload) {
        return elements(upload.body().path("rows"));
    }

    public static List<String> outcomes(Upload upload) {
        assertThat(upload.status()).as("upload: " + upload.body()).isEqualTo(201);
        return rows(upload).stream().map(r -> r.path("outcome").asText()).toList();
    }

    public List<String> rowOutcomeInDatabase(Upload upload) {
        return jdbc.queryForList("SELECT outcome FROM bpa.assignment_file_row WHERE assignment_file_id = ?::uuid ORDER BY row_number", String.class, upload.id());
    }

    public int claims(String sha256, String workpackNumber) {
        return jdbc.queryForObject("SELECT count(*) FROM bpa.published_message WHERE sha256 = ? AND workpack_number = ?", Integer.class, sha256, workpackNumber);
    }

    public int fileRows() {
        return jdbc.queryForObject("SELECT count(*) FROM bpa.assignment_file", Integer.class);
    }

    public String state(Work wp) {
        return jdbc.queryForObject("SELECT assignment_state FROM bpa.workpack WHERE id = ?", String.class, wp.id());
    }

    public static List<String> activeIds(Work wp) {
        return activeElements(wp.instance()).stream().map(e -> e.path("elementId").asText()).toList();
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- BPA-13: replacements ----------------------------------------------------------------------------------------------------

    public static final String END_REPLACED = "EndEvent_AssignmentReplaced";
    public static final String END_NOT_APPLIED = "EndEvent_ReplacementNotApplied";
    private static final String REASSIGNMENT = "Process_PPC_WorkerReassignment";

    /** Waits until no row of the upload reads Pending (the worker has answered) and returns the outcomes in file order (an empty text for a row with no outcome of its own). */
    public List<String> settled(Upload upload) {
        assertThat(upload.status()).as("upload: " + upload.body()).isEqualTo(201);
        return poll(() -> elements(result(upload).path("rows")).stream().map(r -> r.path("outcome").asText()).toList(),
                outcomes -> !outcomes.isEmpty() && !outcomes.contains("Pending"), "the rows of " + upload.id() + " answered");
    }

    /** A Work Pack taken to Assigned the real way: at AwaitingFile, given a file with these Workers and hours, all accepted, and run to EndEvent_WorkPackAssigned (BPA-22, BPA-14, BPA-15). */
    public Work assignedWorkPack(java.util.Map<String, ? extends Number> hoursByBadge) {
        Work wp = awaiting();
        AssignmentFileBuilder file = AssignmentFileBuilder.assignment();
        hoursByBadge.entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(e -> file.row(wp.number(), e.getKey(), e.getValue()));
        Upload upload = upload("production-team", "assign-first-" + wp.number() + ".xlsx", file.build());
        assertThat(settled(upload)).as("every row of the first file is accepted").allMatch("Assigned"::equals);
        awaitEnd(wp);
        assertThat(state(wp)).isEqualTo("Assigned");
        return wp;
    }

    /** The Process_PPC_WorkerReassignment instances started for this Work Pack number (found by the variable the message carries; the Work Pack's own instance is not one of them). */
    public List<String> reassignmentInstances(String workpackNumber) {
        List<String> found = new java.util.ArrayList<>();
        for (JsonNode variable : elements(IntegrationApp.admin.post("/v2/variables/search",
                java.util.Map.of("filter", java.util.Map.of("name", "workpackNumber"), "page", java.util.Map.of("limit", 1000))).body().path("items"))) {
            String instance = variable.path("processInstanceKey").asText();
            if (variable.path("value").asText().contains(workpackNumber) && !found.contains(instance)
                    && REASSIGNMENT.equals(IntegrationApp.admin.get("/v2/process-instances/" + instance).body().path("processDefinitionId").asText())) {
                found.add(instance);
            }
        }
        return found;
    }

    /** Waits until the replacement of this Work Pack has run to the given end event (END_REPLACED or END_NOT_APPLIED), which also frees the Work Pack's key for the next replacement. */
    public void awaitReassignmentEnd(Work wp, String endElementId) {
        awaitReassignmentEnd(wp, endElementId, 1);
    }

    /** Waits until this many replacements of the Work Pack have run to the end event: the second replacement is waited for itself, not satisfied by the first one's end. */
    public void awaitReassignmentEnd(Work wp, String endElementId, int times) {
        poll(() -> reassignmentInstances(wp.number()).stream().filter(i -> completed(Long.parseLong(i), endElementId, 1)).count() >= times, done -> done,
                "the replacement #" + times + " of " + wp.number() + " at " + endElementId);
    }

    public TestBrowser loggedInAs(String username) {
        TestBrowser browser = new TestBrowser(port);
        HttpResponse<String> login = browser.login(username, PersonaSyncTest.DEMO_PASSWORD);
        assertThat(login.statusCode()).as("login " + username + ": " + login.body()).isEqualTo(200);
        return browser;
    }
}
