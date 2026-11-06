package com.lcs.star.bpa;

import static com.lcs.star.bpa.CamundaSteps.elements;
import static com.lcs.star.bpa.CamundaSteps.json;
import static com.lcs.star.bpa.CamundaSteps.poll;
import static com.lcs.star.bpa.CamundaSteps.variable;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcs.star.bpa.AssignmentFlow.Upload;
import com.lcs.star.bpa.AssignmentFlow.Work;
import com.lcs.star.bpa.api.TestBrowser;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
 * BPA-12: uploading a Worker assignment file and routing it, one test per Gherkin scenario (the outline's rows inside its one test).
 * Files come from the AssignmentFileBuilder (09 S18), never from the demo files. Work Packs are taken the real way to the state a
 * scenario needs: Draft (waiting at Confirm), Released (waiting at Schedule) or AwaitingFile (scheduled, printed, its instance waiting at
 * IntermediateCatchEvent_AssignmentFileReceived). A state that no process path produces yet is set in the database and said so:
 * Assigning and Replacing (BPA-22, BPA-13 set them), and AwaitingFile on a Work Pack whose instance is not waiting for the file (the
 * "message cannot be delivered" case). The app runs against the integration Camunda 8.10.1 with its job workers on, as in PrintApiTest.
 * Background: today is 30 Oct 2026 (Asia/Riyadh); Workers E1021 and E1099 are in the Worker master (seed); E9999 is not.
 *
 * <p>The AC's Work Pack numbers (WP-000457, ...) are illustrative: each test creates its own Work Packs and uses the numbers they get.
 * Row validation is BPA-22 (WorkerAssignmentApiTest): a routed instance with valid rows passes ServiceTask_ValidateWorkerAssignment and stops at
 * ServiceTask_CreateHourJournalLines (BPA-14) with an incident "built by BPA-14". The upload answers right after the send, so a routed row reads
 * "Pending" or, when the worker was quicker, "Assigned".
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(AssignmentFileApiTest.TestClockConfig.class)
class AssignmentFileApiTest {

    static final Instant TODAY = Instant.parse("2026-10-30T06:00:00Z"); // 09:00 in Riyadh
    static final String CREATE = "UserTask_CreateWorkPack";
    static final String CONFIRM = "UserTask_ConfirmWorkPack";
    static final String SCHEDULE = "UserTask_ScheduleWorkPack";
    static final String CATCH = "IntermediateCatchEvent_AssignmentFileReceived";
    static final String CREATE_LINES = "ServiceTask_CreateHourJournalLines";
    /** BPA-22 answers a routed row within seconds, so the upload's own answer reads Pending or, when the worker was quicker, Assigned. */
    static final java.util.Set<String> ROUTED = java.util.Set.of("Pending", "Assigned");
    static final String NOT_PROCESSED = "Not processed: Work Pack not ready; resend the file";
    static final String ALREADY = "Already processed in an earlier upload";
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

    @org.junit.jupiter.api.BeforeAll
    static void noInstanceLeftWaitingByAnotherClass() {
        IntegrationApp.background();
        CamundaSteps.cancelInstancesWaitingAt(CATCH);
    }

    @BeforeEach
    void today() {
        clock.set(TODAY);
    }

    // --- the scenarios ----------------------------------------------------------------------------------------------------

    @Test
    void BPA12_fileForScheduledWorkPackIsRouted() {
        Work wp = awaiting();
        byte[] file = AssignmentFileBuilder.assignment().row(wp.number(), "E1021", 40).row(wp.number(), "E1099", 60).build();

        Upload upload = upload("production-team", "assign-01.xlsx", file);

        assertThat(upload.status()).isEqualTo(201);
        assertThat(upload.body().has("outcome")).as("no file-level line: the rows carry the outcomes").isFalse();
        // "Then the on-screen result shows both rows as Pending until BPA-22 validates them" (or Assigned, when it was quicker), with the Worker names (D59)
        assertThat(rows(upload)).extracting(r -> r.path("outcome").asText()).hasSize(2).allMatch(ROUTED::contains);
        assertThat(rows(upload)).extracting(r -> r.path("workerName").asText()).containsExactly("Worker E1021", "Worker E1099");
        assertThat(claims(sha256(file), wp.number())).as("published_message claim for the Work Pack").isEqualTo(1);
        // "Then message AssignmentFileReceived is published with correlation key WP / And the instance continues to ServiceTask_ValidateWorkerAssignment"
        poll(() -> activeIds(wp), ids -> ids.contains(CREATE_LINES), "the instance of " + wp.number() + " at " + CREATE_LINES);
        assertThat(poll(() -> variable(wp.instance(), "assignmentFileId"), v -> v != null, "assignmentFileId of " + wp.number()).replace("\"", ""))
                .as("the message carries the upload's id").isEqualTo(upload.id());
    }

    @Test
    void BPA12_workPackNotReleased() {
        Work wp = draft();
        byte[] file = AssignmentFileBuilder.assignment().row(wp.number(), "E1021", 40).build();

        Upload upload = upload("production-team", "assign-draft.xlsx", file);

        assertThat(outcomes(upload)).containsExactly("Work Pack is not Released");
        assertThat(claims(sha256(file), wp.number())).as("no message is published").isZero();
    }

    @Test
    void BPA12_workPackNotReadyForAssignment() {
        Work wp = released(); // Released, waiting to be scheduled: no planned dates, so no print and no assignment_state (D50)
        byte[] file = AssignmentFileBuilder.assignment().row(wp.number(), "E1021", 40).build();

        Upload upload = upload("production-team", "assign-not-ready.xlsx", file);

        assertThat(outcomes(upload)).containsExactly("Work Pack not ready for assignment yet");
        assertThat(claims(sha256(file), wp.number())).isZero();
    }

    @Test
    void BPA12_messageNotDeliverableRowsNotProcessed() {
        // Given the Work Pack is AwaitingFile but its instance cannot receive the message: arranged by hand, since the process only
        // sets AwaitingFile after the print, when the instance is already waiting. Here the instance waits at Schedule Work Pack.
        Work wp = released();
        jdbc.update("UPDATE bpa.workpack SET assignment_state = 'AwaitingFile' WHERE id = ?", wp.id());
        byte[] file = AssignmentFileBuilder.assignment().row(wp.number(), "E1021", 40).build();

        Upload upload = upload("production-team", "assign-undeliverable.xlsx", file);

        assertThat(outcomes(upload)).as("at once, on screen").containsExactly(NOT_PROCESSED);
        assertThat(claims(sha256(file), wp.number())).as("the claim is undone so a resend is accepted (C2)").isZero();
        assertThat(state(wp)).as("the Work Pack stays AwaitingFile").isEqualTo("AwaitingFile");
        assertThat(rowOutcomeInDatabase(upload)).as("and the stored row says the same").containsExactly(NOT_PROCESSED);
    }

    @Test
    void BPA12_assignmentInProgressRejectsUpload() {
        // Examples: | Assigning | Assignment still in progress; try again later |, | Replacing | Replacement already in progress |
        // Both states are set by hand: BPA-22 sets Assigning and BPA-13 sets Replacing, neither is built.
        Map<String, String> examples = new LinkedHashMap<>();
        examples.put("Assigning", "Assignment still in progress; try again later");
        examples.put("Replacing", "Replacement already in progress");
        examples.forEach((stateName, message) -> {
            Work wp = released();
            jdbc.update("UPDATE bpa.workpack SET assignment_state = ? WHERE id = ?", stateName, wp.id());
            byte[] file = AssignmentFileBuilder.assignment().row(wp.number(), "E1021", 40).build();

            Upload upload = upload("production-team", "assign-" + stateName + ".xlsx", file);

            assertThat(outcomes(upload)).as(stateName).containsExactly(message);
            assertThat(claims(sha256(file), wp.number())).as(stateName + ": no message is published").isZero();
            assertThat(state(wp)).as(stateName + " is left as it was").isEqualTo(stateName);
        });
    }

    @Test
    void BPA12_workPackNotFound() {
        Upload upload = upload("production-team", "assign-unknown.xlsx", AssignmentFileBuilder.assignment().row("WP-999999", "E1021", 40).build());

        assertThat(outcomes(upload)).containsExactly("Work Pack not found");
    }

    @Test
    void BPA12_fileWithSeveralWorkPacks() {
        Work waiting = awaiting();
        Work notReleased = draft();
        byte[] file = AssignmentFileBuilder.assignment().row(waiting.number(), "E1021", 40).row(notReleased.number(), "E1099", 10).build();

        Upload upload = upload("production-team", "assign-two.xlsx", file);

        assertThat(outcomes(upload)).satisfiesExactly(o -> assertThat(o).isIn(ROUTED), o -> assertThat(o).isEqualTo("Work Pack is not Released"));
        assertThat(claims(sha256(file), waiting.number())).as("published for the first Work Pack only").isEqualTo(1);
        assertThat(claims(sha256(file), notReleased.number())).isZero();
        poll(() -> activeIds(waiting), ids -> ids.contains(CREATE_LINES), "the instance of " + waiting.number() + " at " + CREATE_LINES);
    }

    @Test
    void BPA12_fileHasNoRows() {
        int before = fileRows();

        Upload upload = upload("production-team", "assign-empty.xlsx", AssignmentFileBuilder.assignment().build());

        assertThat(upload.status()).isEqualTo(201);
        assertThat(upload.body().path("outcome").asText()).as("one line").isEqualTo("File has no rows");
        assertThat(rows(upload)).isEmpty();
        assertThat(fileRows()).as("the upload is recorded").isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("SELECT outcome FROM bpa.assignment_file WHERE id = ?::uuid", String.class, upload.id())).isEqualTo("File has no rows");
    }

    @Test
    void BPA12_fileCannotBeRead() {
        Upload upload = upload("production-team", "assign-renamed.xlsx", AssignmentFileBuilder.csvRenamedAsXlsx());

        assertThat(upload.status()).isEqualTo(201);
        assertThat(upload.body().path("outcome").asText()).isEqualTo("File cannot be read");
        assertThat(rows(upload)).isEmpty();
    }

    @Test
    void BPA12_fileLayoutNotRecognised() {
        // "a file whose first sheet lacks the columns Workpack number, Badge No and Estimated hours"
        Upload upload = upload("production-team", "assign-layout.xlsx",
                AssignmentFileBuilder.assignment().headers("Employee", "Hours").cells("E1021", 40).build());

        assertThat(upload.body().path("outcome").asText()).isEqualTo("File layout not recognised");
        assertThat(rows(upload)).isEmpty();
    }

    @Test
    void BPA12_sameFileAgainIsIgnored() {
        Work wp = awaiting();
        byte[] file = AssignmentFileBuilder.assignment().row(wp.number(), "E1021", 40).row(wp.number(), "E1099", 60).build();
        assertThat(outcomes(upload("production-team", "assign-01.xlsx", file))).hasSize(2).allMatch(ROUTED::contains);
        poll(() -> activeIds(wp), ids -> ids.contains(CREATE_LINES), "the instance of " + wp.number() + " at " + CREATE_LINES);
        int filesBefore = fileRows();

        // identical content (same SHA-256) under a different name
        Upload again = upload("production-team", "assign-01-copy.xlsx", file);

        assertThat(again.body().path("outcome").asText()).as("the one line").isEqualTo("File already processed");
        assertThat(rows(again)).isEmpty();
        assertThat(fileRows()).as("recorded as its own assignment_file row").isEqualTo(filesBefore + 1);
        assertThat(jdbc.queryForObject("SELECT outcome FROM bpa.assignment_file WHERE id = ?::uuid", String.class, again.id())).isEqualTo("File already processed");
        assertThat(claims(sha256(file), wp.number())).as("no message is published again").isEqualTo(1);
        assertThat(activeIds(wp)).as("and nothing changes in the process").contains(CREATE_LINES);
    }

    @Test
    void BPA12_resendOfUnroutedFileIsAccepted() {
        // Given assign-02.xlsx was uploaded for a Work Pack and its row read "Not processed ..." (the instance could not receive it)
        Work wp = released();
        jdbc.update("UPDATE bpa.workpack SET assignment_state = 'AwaitingFile' WHERE id = ?", wp.id()); // by hand, as in the not-deliverable test
        byte[] file = AssignmentFileBuilder.assignment().row(wp.number(), "E1021", 40).build();
        assertThat(outcomes(upload("production-team", "assign-02.xlsx", file))).containsExactly(NOT_PROCESSED);

        // And the Work Pack now waits at IntermediateCatchEvent_AssignmentFileReceived (the real print step brings it there)
        schedule(wp);

        // When I upload assign-02.xlsx again with identical content
        Upload resend = upload("production-team", "assign-02.xlsx", file);

        assertThat(outcomes(resend)).hasSize(1).allMatch(ROUTED::contains);
        assertThat(resend.body().has("outcome")).as("the result does not show \"File already processed\"").isFalse();
        assertThat(claims(sha256(file), wp.number())).isEqualTo(1);
        poll(() -> activeIds(wp), ids -> ids.contains(CREATE_LINES), "the instance of " + wp.number() + " at " + CREATE_LINES);
    }

    @Test
    void BPA12_resendIsDuplicateOnlyForPublishedWorkPacks() {
        // Given assign-03.xlsx has rows for A (published) and B (not routed: its instance cannot receive the message yet)
        Work a = awaiting();
        Work b = released();
        jdbc.update("UPDATE bpa.workpack SET assignment_state = 'AwaitingFile' WHERE id = ?", b.id()); // by hand
        byte[] file = AssignmentFileBuilder.assignment().row(a.number(), "E1021", 40).row(b.number(), "E1099", 10).build();
        assertThat(outcomes(upload("production-team", "assign-03.xlsx", file))).satisfiesExactly(o -> assertThat(o).isIn(ROUTED), o -> assertThat(o).isEqualTo(NOT_PROCESSED));
        poll(() -> activeIds(a), ids -> ids.contains(CREATE_LINES), "the instance of " + a.number() + " at " + CREATE_LINES);
        // And B now waits at the catch event
        schedule(b);

        // When I upload assign-03.xlsx again with identical content
        Upload resend = upload("production-team", "assign-03.xlsx", file);

        assertThat(outcomes(resend)).as("only B is published; A is a duplicate (D73)").satisfiesExactly(o -> assertThat(o).isEqualTo(ALREADY), o -> assertThat(o).isIn(ROUTED));
        assertThat(resend.body().has("outcome")).as("the result holds no file-level line").isFalse();
        assertThat(claims(sha256(file), a.number())).isEqualTo(1);
        assertThat(claims(sha256(file), b.number())).isEqualTo(1);
        poll(() -> activeIds(b), ids -> ids.contains(CREATE_LINES), "the instance of " + b.number() + " at " + CREATE_LINES);
    }

    @Test
    void BPA12_uploadIsRecorded() {
        Work wp = awaiting();
        byte[] file = AssignmentFileBuilder.assignment().row(wp.number(), "E1021", 40).row(wp.number(), "E1099", 60).build();

        Upload upload = upload("production-team", "assign-01.xlsx", file);

        // assignment_file holds one row with its sha256, my Camunda username and no file-level outcome
        Map<String, Object> header = jdbc.queryForMap("SELECT * FROM bpa.assignment_file WHERE id = ?::uuid", upload.id());
        assertThat(header.get("file_name")).isEqualTo("assign-01.xlsx");
        assertThat(header.get("sha256")).isEqualTo(sha256(file));
        assertThat(header.get("uploaded_by")).isEqualTo("production-team");
        assertThat(header.get("outcome")).as("the rows carry the outcomes").isNull();
        assertThat(((java.sql.Timestamp) header.get("received_at")).toInstant()).as("from the clock (S9)").isEqualTo(TODAY);
        // assignment_file_row holds 2 rows with their row number, Workpack number, Badge No and outcome
        List<Map<String, Object>> stored = jdbc.queryForList(
                "SELECT row_number, workpack_number, badge_no, estimated_hours_raw, outcome FROM bpa.assignment_file_row WHERE assignment_file_id = ?::uuid ORDER BY row_number",
                upload.id());
        assertThat(stored).hasSize(2);
        assertThat(stored.get(0)).containsEntry("row_number", 2).containsEntry("workpack_number", wp.number())
                .containsEntry("badge_no", "E1021").containsEntry("estimated_hours_raw", "40");
        assertThat((String) stored.get(0).get("outcome")).as("Pending, or Assigned when BPA-22 was quicker than this read").isIn(ROUTED);
        assertThat(stored.get(1)).containsEntry("row_number", 3).containsEntry("badge_no", "E1099").containsEntry("estimated_hours_raw", "60");
        // GET /api/v1/assignment-files/{id}/result returns the same rows for download, once BPA-22 has answered them (they read Assigned)
        CamundaSteps.poll(() -> flow.result(upload).path("rows").findValuesAsText("outcome"), o -> !o.contains("Pending"), "the rows of the upload answered");
        TestBrowser team = loggedInAs("production-team");
        HttpResponse<String> result = team.get("/api/v1/assignment-files/" + upload.id() + "/result");
        assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
        assertThat(rows(new Upload(200, json(result.body())))).extracting(r -> r.path("workpackNumber").asText() + "/" + r.path("badgeNo").asText()
                + "/" + r.path("outcome").asText()).containsExactly(wp.number() + "/E1021/Assigned", wp.number() + "/E1099/Assigned");
        HttpResponse<String> csv = team.getWithAccept("/api/v1/assignment-files/" + upload.id() + "/result", "text/csv");
        assertThat(csv.statusCode()).as(csv.body()).isEqualTo(200);
        assertThat(csv.headers().firstValue("Content-Type")).hasValueSatisfying(t -> assertThat(t).startsWith("text/csv"));
        assertThat(csv.headers().firstValue("Content-Disposition")).hasValue("attachment; filename=\"assign-01-result.csv\"");
        assertThat(csv.body().trim().split("\\R")).containsExactly(
                "rowNumber,workpackNumber,badgeNo,workerName,outcome,wpOutcome",
                "2," + wp.number() + ",E1021,Worker E1021,Assigned,",
                "3," + wp.number() + ",E1099,Worker E1099,Assigned,");
    }

    @Test
    void resultIsJsonWhenTheAcceptHeaderIsAnyTypeOrMissing() {
        // Not a scenario: a client that sends Accept: */* (curl, a script) or no Accept at all gets JSON, not the CSV (review).
        Upload upload = upload("production-team", "assign-empty.xlsx", AssignmentFileBuilder.assignment().build());
        TestBrowser team = loggedInAs("production-team");

        for (HttpResponse<String> any : List.of(
                team.getWithAccept("/api/v1/assignment-files/" + upload.id() + "/result", "*/*"),
                team.get("/api/v1/assignment-files/" + upload.id() + "/result"))) {
            assertThat(any.statusCode()).as(any.body()).isEqualTo(200);
            assertThat(any.headers().firstValue("Content-Type")).hasValueSatisfying(t -> assertThat(t).startsWith("application/json"));
            assertThat(json(any.body()).path("outcome").asText()).isEqualTo("File has no rows");
        }
    }

    @Test
    void BPA12_otherRolesCannotUpload() {
        int before = fileRows();

        Upload upload = upload("production-planner", "assign-01.xlsx", AssignmentFileBuilder.assignment().row("WP-000457", "E1021", 40).build());

        assertThat(upload.status()).isEqualTo(403);
        assertThat(fileRows()).as("no assignment_file row is created").isEqualTo(before);
    }

    // --- not scenarios ----------------------------------------------------------------------------------------------------

    /** A row with no Workpack number reaches no instance, so it is answered at intake (data model 7.3). */
    @Test
    void aRowWithoutAWorkpackNumberIsAnsweredAtIntake() {
        Upload upload = upload("production-team", "assign-blank.xlsx", AssignmentFileBuilder.assignment().cells(null, "E1021", 40).build());

        assertThat(outcomes(upload)).containsExactly("Workpack number is required");
    }

    /** The Worker name comes from the Worker master and is absent when the Badge No is not in it (ASSUMPTION, B15). */
    @Test
    void anUnknownBadgeNoHasNoWorkerName() {
        Work wp = awaiting();

        Upload upload = upload("production-team", "assign-unknown-worker.xlsx",
                AssignmentFileBuilder.assignment().row(wp.number(), "E1021", 40).row(wp.number(), "E9999", 5).build());

        assertThat(rows(upload).get(0).path("workerName").asText()).isEqualTo("Worker E1021");
        assertThat(rows(upload).get(1).has("workerName")).as("E9999 is not in the Worker master").isFalse();
        assertThat(rows(upload).get(1).path("badgeNo").asText()).isEqualTo("E9999");
    }

    /** BPA-14 builds the next step: until then an instance with Assigned Workers stops there with an incident saying so (as BPA-6 did for BPA-23). */
    @Test
    void aRoutedInstanceStopsWithTheBpa14Incident() {
        Work wp = awaiting();
        assertThat(outcomes(upload("production-team", "assign-incident.xlsx", AssignmentFileBuilder.assignment().row(wp.number(), "E1021", 40).build())))
                .hasSize(1).allMatch(ROUTED::contains);

        List<JsonNode> incidents = poll(() -> elements(IntegrationApp.admin.post("/v2/incidents/search",
                        Map.of("filter", Map.of("processInstanceKey", Long.toString(wp.instance())))).body().path("items")),
                list -> !list.isEmpty(), "an incident on the instance of " + wp.number());

        assertThat(incidents.get(0).path("errorMessage").asText()).contains("built by BPA-14");
    }

    @Test
    void anUploadWithoutAFileIs400() {
        HttpResponse<String> response = loggedInAs("production-team").postFile("/api/v1/assignment-files", "x.xlsx", null);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(json(response.body()).path("detail").asText()).isEqualTo("File is required");
    }

    @Test
    void theResultOfAnUnknownUploadIs404AndOnlyTheProductionTeamReadsIt() {
        String unknown = "/api/v1/assignment-files/" + UUID.randomUUID() + "/result";

        HttpResponse<String> missing = loggedInAs("production-team").get(unknown);
        assertThat(missing.statusCode()).as(missing.body()).isEqualTo(404);
        assertThat(json(missing.body()).path("detail").asText()).isEqualTo("Assignment file not found");
        assertThat(loggedInAs("production-planner").get(unknown).statusCode()).isEqualTo(403);
        assertThat(loggedInAs("production-planner").head(unknown).statusCode()).as("HEAD follows the same rule").isEqualTo(403);
        assertThat(new TestBrowser(port).get(unknown).statusCode()).isEqualTo(401);
    }

    /** "Download template" (S18): the official template, for the production-team only (a new operation of this story, x-assumption). */
    @Test
    void theTemplateCanBeDownloaded() {
        HttpResponse<byte[]> response = loggedInAs("production-team").getBytes("/api/v1/assignment-files/template");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(response.headers().firstValue("Content-Disposition")).hasValue("attachment; filename=\"worker-assignment-template.xlsx\"");
        assertThat(com.lcs.star.bpa.assignment.AssignmentFileReader.read(response.body()).fileOutcome()).as("a header only").isEqualTo("File has no rows");
        assertThat(loggedInAs("production-planner").getBytes("/api/v1/assignment-files/template").statusCode()).isEqualTo(403);
    }

    // --- Work Packs and uploads: the shared set-up is in AssignmentFlow (BPA-22 uses it too) -----------------------------------

    private AssignmentFlow flow;

    @BeforeEach
    void flow() {
        flow = new AssignmentFlow(port, jdbc);
    }

    private Work draft() {
        return flow.draft();
    }

    private Work released() {
        return flow.released();
    }

    private void schedule(Work wp) {
        flow.schedule(wp);
    }

    private Work awaiting() {
        return flow.awaiting();
    }

    private Upload upload(String user, String fileName, byte[] file) {
        return flow.upload(user, fileName, file);
    }

    private static List<JsonNode> rows(Upload upload) {
        return AssignmentFlow.rows(upload);
    }

    private static List<String> outcomes(Upload upload) {
        return AssignmentFlow.outcomes(upload);
    }

    private List<String> rowOutcomeInDatabase(Upload upload) {
        return flow.rowOutcomeInDatabase(upload);
    }

    private int claims(String sha256, String workpackNumber) {
        return flow.claims(sha256, workpackNumber);
    }

    private int fileRows() {
        return flow.fileRows();
    }

    private String state(Work wp) {
        return flow.state(wp);
    }

    private static List<String> activeIds(Work wp) {
        return AssignmentFlow.activeIds(wp);
    }

    private static String sha256(byte[] bytes) {
        return AssignmentFlow.sha256(bytes);
    }

    private TestBrowser loggedInAs(String username) {
        return flow.loggedInAs(username);
    }

    /** "Given today is 30 Oct 2026 (Asia/Riyadh)" (S9; ADR 0013). */
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
