package com.lcs.star.bpa;

import static com.lcs.star.bpa.CamundaSteps.activeElements;
import static com.lcs.star.bpa.CamundaSteps.awaitOpenTask;
import static com.lcs.star.bpa.CamundaSteps.awaitSignOffTask;
import static com.lcs.star.bpa.CamundaSteps.completeSignOff;
import static com.lcs.star.bpa.CamundaSteps.elements;
import static com.lcs.star.bpa.CamundaSteps.json;
import static com.lcs.star.bpa.CamundaSteps.poll;
import static com.lcs.star.bpa.CamundaSteps.toJson;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcs.star.bpa.api.TestBrowser;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    // --- Work Packs in the state a scenario needs ---------------------------------------------------------------------------

    public Work draft() {
        TestBrowser analyst = loggedInAs("time-method-analyst");
        HttpResponse<String> started = analyst.post("/api/v1/workpacks", "");
        assertThat(started.statusCode()).as("POST /api/v1/workpacks: " + started.body()).isEqualTo(201);
        long instance = Long.parseLong(json(started.body()).path("processInstanceKey").asText());
        String create = awaitOpenTask(instance, CREATE).path("userTaskKey").asText();
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("activityRef", "A-2201");
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
        Work draft = draft();
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
        Work wp = released();
        schedule(wp);
        assertThat(state(wp)).isEqualTo("AwaitingFile");
        return wp;
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

    public TestBrowser loggedInAs(String username) {
        TestBrowser browser = new TestBrowser(port);
        HttpResponse<String> login = browser.login(username, PersonaSyncTest.DEMO_PASSWORD);
        assertThat(login.statusCode()).as("login " + username + ": " + login.body()).isEqualTo(200);
        return browser;
    }
}
