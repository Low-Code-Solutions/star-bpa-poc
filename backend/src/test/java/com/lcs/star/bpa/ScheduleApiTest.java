package com.lcs.star.bpa;

import static com.lcs.star.bpa.CamundaSteps.assertStaysEmpty;
import static com.lcs.star.bpa.CamundaSteps.awaitOpenTask;
import static com.lcs.star.bpa.CamundaSteps.awaitSignOffTask;
import static com.lcs.star.bpa.CamundaSteps.completeSignOff;
import static com.lcs.star.bpa.CamundaSteps.elements;
import static com.lcs.star.bpa.CamundaSteps.json;
import static com.lcs.star.bpa.CamundaSteps.openTasks;
import static com.lcs.star.bpa.CamundaSteps.poll;
import static com.lcs.star.bpa.CamundaSteps.toJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcs.star.bpa.api.TestBrowser;
import com.lcs.star.bpa.worker.SaveWorkPackScheduleWorker;
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
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BPA-11: Schedule Work Pack and the Look-Ahead read API, one test per Gherkin scenario (the outline's rows inside its one test).
 * The Background goes the real way: create the Work Pack, complete Confirm Workpack unchanged, the release check returns Ready, the
 * Project Engineer approves Sign off release, and the process waits at UserTask_ScheduleWorkPack (production-planner). The app runs
 * against the integration Camunda 8.10.1 with its job workers on, as in SignOffApiTest (BPA-9). Today is 21 Oct 2026 (Asia/Riyadh)
 * and the lead time is 7 days, so the earliest allowed date is 28 Oct 2026.
 *
 * <p>Test data (PO decision for BPA-8, 5 Nov 2026): the seed does not match the story's Givens (see the plan), so a test arranges
 * exactly the Given for the drawing it names, and {@link #restoreSeed()} puts the seed rows back afterwards.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(ScheduleApiTest.TestClockConfig.class)
class ScheduleApiTest {

    static final Instant TODAY = Instant.parse("2026-10-21T06:00:00Z"); // 09:00 in Riyadh
    static final String CREATE = "UserTask_CreateWorkPack";
    static final String CONFIRM = "UserTask_ConfirmWorkPack";
    static final String SCHEDULE = "UserTask_ScheduleWorkPack";
    static final String TOO_EARLY = "Date cannot be earlier than 28 Oct 2026";
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
    SaveWorkPackScheduleWorker worker;

    @Autowired
    Environment environment;

    /** The seed rows of each drawing this test touched, kept the first time it is arranged. */
    private final Map<String, Seed> touched = new LinkedHashMap<>();

    @BeforeEach
    void today() {
        clock.set(TODAY);
    }

    @AfterEach
    void restoreSeed() {
        touched.forEach((drawing, seed) -> {
            jdbc.update("DELETE FROM bpa.lookahead_item WHERE design_dwg_ref = ?", drawing);
            jdbc.update("DELETE FROM bpa.drawing_revision WHERE drawing_ref = ?", drawing);
            jdbc.update("DELETE FROM bpa.material_reservation WHERE drawing_ref = ?", drawing);
            seed.revisions().forEach(row -> insert("drawing_revision", row));
            seed.reservations().forEach(row -> insert("material_reservation", row));
            seed.lookAhead().forEach(row -> insert("lookahead_item", row));
        });
        touched.clear();
    }

    // --- the scenarios ----------------------------------------------------------------------------------------------------

    @Test
    void BPA11_workPackIsScheduledWithLookAheadDates() {
        // Given the Look-Ahead row for DWG-118 has Shop Drg Mark 03 Nov, Assembly / Integration 10 Nov, Weld Map Drawing 05 Nov
        setLookAhead("DWG-118", "2026-11-03", "2026-11-10", "2026-11-05");
        Scheduling wp = atSchedule("DWG-118");
        TestBrowser planner = loggedInAs("production-planner");

        // When I open the Schedule Work Pack task: the three dates and the date to complete are there to show, read-only
        HttpResponse<String> lookAhead = planner.get("/api/v1/workpacks/" + wp.number() + "/look-ahead");
        assertThat(lookAhead.statusCode()).as(lookAhead.body()).isEqualTo(200);
        JsonNode dates = json(lookAhead.body());
        assertThat(dates.path("workpackNumber").asText()).isEqualTo(wp.number());
        assertThat(dates.path("drawingRef").asText()).isEqualTo("DWG-118");
        assertThat(dates.path("shopDrgMarkPlanDate").asText()).isEqualTo("2026-11-03");
        assertThat(dates.path("assemblyPlanDate").asText()).isEqualTo("2026-11-10");
        assertThat(dates.path("weldMapPlanDate").asText()).isEqualTo("2026-11-05");
        JsonNode task = json(planner.get("/api/v1/tasks/" + wp.scheduleTask()).body());
        assertThat(task.path("variables").path("completionDate").asText()).as("date to complete, for the screen").isEqualTo("2026-11-06");
        assertThat(task.path("variables").path("drawingRef").asText()).isEqualTo("DWG-118");

        // When I enter planned start 03 Nov 2026 and planned end 06 Nov 2026 and complete the task
        Outcome outcome = submit(planner, wp, "2026-11-03", "2026-11-06");

        // Then the Work Pack has those planned dates
        assertThat(outcome.saved()).isTrue();
        Map<String, Object> row = scheduleRow(wp);
        assertThat(row.get("planned_start_date").toString()).isEqualTo("2026-11-03");
        assertThat(row.get("planned_end_date").toString()).isEqualTo("2026-11-06");
        assertThat(row.get("scheduled_by")).as("the Camunda username, D63").isEqualTo("production-planner");
        // The audit rows (D64, data model §6.1): one per changed business column, by the Production Planner.
        assertThat(jdbc.queryForList("""
                SELECT column_name || '=' || new_value FROM bpa.audit_log
                WHERE table_name = 'workpack_schedule' AND record_id = ? AND old_value IS NULL AND username = 'production-planner'
                ORDER BY column_name""", String.class, row.get("id")))
                .containsExactly("planned_end_date=2026-11-06", "planned_start_date=2026-11-03", "scheduled_by=production-planner",
                        "workpack_id=" + wp.id());
        // The process moves on to Generate Workpack print (BPA-10 builds its worker; until then it waits there).
        poll(() -> CamundaSteps.activeElements(wp.instance()).stream().map(e -> e.path("elementId").asText()).toList(),
                ids -> ids.contains("ServiceTask_GenerateWorkPackPrint"), "the instance of " + wp.number() + " at Generate Workpack print");
    }

    @Test
    void BPA11_plannedDateBeforeLeadTimeIsRejected() {
        // Examples: | planned start | 27 Oct 2026 | rejected |, | planned start | 28 Oct 2026 | accepted |,
        //           | planned end   | 27 Oct 2026 | rejected |
        TestBrowser planner = loggedInAs("production-planner");

        Scheduling startTooEarly = atSchedule("DWG-118");
        Outcome first = submit(planner, startTooEarly, "2026-10-27", "2026-11-06");
        assertThat(first.saved()).isFalse();
        assertThat(first.messages()).containsExactly(TOO_EARLY);
        assertThat(scheduleRows(startTooEarly)).isZero();

        Scheduling startOnTheBoundary = atSchedule("DWG-118");
        Outcome second = submit(planner, startOnTheBoundary, "2026-10-28", "2026-11-06");
        assertThat(second.saved()).as("28 Oct is today + 7 days: accepted").isTrue();
        assertThat(scheduleRow(startOnTheBoundary).get("planned_start_date").toString()).isEqualTo("2026-10-28");

        Scheduling endTooEarly = atSchedule("DWG-118");
        Outcome third = submit(planner, endTooEarly, "2026-11-03", "2026-10-27");
        assertThat(third.saved()).isFalse();
        assertThat(third.messages()).as("lead time first, so no \"end before start\" message").containsExactly(TOO_EARLY);
        assertThat(scheduleRows(endTooEarly)).isZero();
    }

    @Test
    void BPA11_plannedEndBeforeStartIsRejected() {
        Scheduling wp = atSchedule("DWG-118");
        TestBrowser planner = loggedInAs("production-planner");

        Outcome outcome = submit(planner, wp, "2026-11-05", "2026-11-04");

        assertThat(outcome.saved()).isFalse();
        assertThat(outcome.messages()).containsExactly("Planned end cannot be before planned start");
        assertThat(scheduleRows(wp)).as(wp.number() + " has no planned dates").isZero();

        // The returned task takes the corrected dates, and the messages are gone.
        Outcome corrected = submit(planner, wp.withTask(outcome.taskKey()), "2026-11-03", "2026-11-06");
        assertThat(corrected.saved()).isTrue();
    }

    @Test
    void BPA11_lateEndShowsNotice() {
        // The notice is the screen's (React test of the same name); the save succeeds (D26).
        Scheduling wp = atSchedule("DWG-118"); // date to complete 06 Nov 2026
        TestBrowser planner = loggedInAs("production-planner");

        Outcome outcome = submit(planner, wp, "2026-11-03", "2026-11-12");

        assertThat(outcome.saved()).as("an end after the date to complete is not an error").isTrue();
        Map<String, Object> row = scheduleRow(wp);
        assertThat(row.get("planned_start_date").toString()).isEqualTo("2026-11-03");
        assertThat(row.get("planned_end_date").toString()).isEqualTo("2026-11-12");
    }

    @Test
    void BPA11_noLookAheadRowForDrawing() {
        // Given no Look-Ahead row exists for drawing DWG-121 (the seed has one, and its latest revision is IFI: arranged as the Given)
        approvableDrawing("DWG-121");
        removeLookAhead("DWG-121");
        Scheduling wp = atSchedule("DWG-121");
        TestBrowser planner = loggedInAs("production-planner");

        HttpResponse<String> lookAhead = planner.get("/api/v1/workpacks/" + wp.number() + "/look-ahead");

        assertThat(lookAhead.statusCode()).as(lookAhead.body()).isEqualTo(200);
        JsonNode body = json(lookAhead.body());
        assertThat(body.path("drawingRef").asText()).isEqualTo("DWG-121");
        assertThat(body.has("shopDrgMarkPlanDate")).as("no dates").isFalse();
        assertThat(body.has("assemblyPlanDate")).isFalse();
        assertThat(body.has("weldMapPlanDate")).isFalse();
        // "And I can still enter planned dates and complete the task"
        assertThat(submit(planner, wp, "2026-11-03", "2026-11-06").saved()).isTrue();
    }

    @Test
    void BPA11_lookAheadForUnknownWorkPackReturnsNotFound() {
        TestBrowser planner = loggedInAs("production-planner");

        HttpResponse<String> response = planner.get("/api/v1/workpacks/WP-999999/look-ahead");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(t -> assertThat(t).startsWith("application/problem+json"));
        assertThat(json(response.body()).path("detail").asText()).isEqualTo("Work Pack not found");
    }

    @Test
    void BPA11_otherRolesCannotSchedule() {
        Scheduling wp = atSchedule("DWG-118");
        TestBrowser analyst = loggedInAs("time-method-analyst");

        // "When I list my tasks": the Schedule Work Pack task is not shown
        HttpResponse<String> list = analyst.get("/api/v1/tasks");
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(elements(json(list.body()))).extracting(t -> t.path("key").asText()).doesNotContain(wp.scheduleTask());

        // "And POST /api/v1/tasks/{key}/complete for that task returns 403"
        HttpResponse<String> response = complete(analyst, wp.scheduleTask(), "2026-11-03", "2026-11-06");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
        assertThat(openTasks(wp.instance(), SCHEDULE)).as("still awaits the Production Planner").hasSize(1);
        assertThat(scheduleRows(wp)).isZero();
    }

    @Test
    void BPA11_scheduledWorkPackCannotBeRescheduled() {
        Scheduling wp = atSchedule("DWG-118");
        TestBrowser planner = loggedInAs("production-planner");
        assertThat(submit(planner, wp, "2026-11-03", "2026-11-06").saved()).isTrue();
        int auditRows = scheduleAuditRows(wp);

        // "Then no UserTask_ScheduleWorkPack task exists for WP": the process has moved on and nothing offers the task again
        assertStaysEmpty(() -> openTasks(wp.instance(), SCHEDULE), "Schedule Work Pack task for " + wp.number());
        // "And its planned dates are unchanged", even if the job for this Work Pack runs again with other dates (D27, D65)
        JobClient client = Mockito.mock(JobClient.class, Mockito.RETURNS_DEEP_STUBS);
        worker.handle(client, job(81L, wp.instance(), Map.of("completedBy", "production-planner",
                "plannedStartDate", "2026-11-10", "plannedEndDate", "2026-11-12")));
        Mockito.verify(client).newCompleteCommand(81L);
        Map<String, Object> row = scheduleRow(wp);
        assertThat(row.get("planned_start_date").toString()).isEqualTo("2026-11-03");
        assertThat(row.get("planned_end_date").toString()).isEqualTo("2026-11-06");
        assertThat(scheduleRows(wp)).as("one schedule per Work Pack").isEqualTo(1);
        assertThat(scheduleAuditRows(wp)).as("no audit row for a re-run").isEqualTo(auditRows);
    }

    // --- not scenarios ----------------------------------------------------------------------------------------------------

    /** The lead time is configuration (data model §6.2: app.lead-time-days, default 7), not a constant in the code. */
    @Test
    void leadTimeIsConfiguredAsSevenDays() {
        assertThat(environment.getProperty("app.lead-time-days")).isEqualTo("7");
    }

    /** A missing date reaches the save step through the task API too: the task returns showing it (ASSUMPTION texts). */
    @Test
    void missingDatesAreRefusedByTheSaveStep() {
        Scheduling wp = atSchedule("DWG-118");
        TestBrowser planner = loggedInAs("production-planner");

        HttpResponse<String> done = planner.post("/api/v1/tasks/" + wp.scheduleTask() + "/complete",
                toJson(Map.of("variables", Map.of("plannedStartDate", "2026-11-03"))));
        assertThat(done.statusCode()).as(done.body()).isEqualTo(204);

        JsonNode back = poll(() -> openTasks(wp.instance(), SCHEDULE).stream()
                .filter(t -> !wp.scheduleTask().equals(t.path("userTaskKey").asText())).toList(), list -> !list.isEmpty(),
                "Schedule Work Pack returned for " + wp.number()).get(0);
        JsonNode task = json(planner.get("/api/v1/tasks/" + back.path("userTaskKey").asText()).body());
        List<String> messages = new ArrayList<>();
        task.path("validationMessages").forEach(m -> messages.add(m.asText()));
        assertThat(messages).containsExactly("Planned end is required");
        assertThat(task.path("variables").path("plannedStartDate").asText()).as("what was entered comes back").isEqualTo("2026-11-03");
        assertThat(scheduleRows(wp)).isZero();
    }

    /** A malformed Work Pack number is the contract's InvalidWorkpackNumber (the same text as the other workpackNumber reads). */
    @Test
    void lookAheadWithAMalformedNumberIsBadRequest() {
        HttpResponse<String> response = loggedInAs("production-planner").get("/api/v1/workpacks/WP-12/look-ahead");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(json(response.body()).path("detail").asText()).isEqualTo("Workpack number must match WP-######");
    }

    /** The look-ahead is open to every logged-in user (x-assumption in the contract), and to nobody else. */
    @Test
    void lookAheadNeedsALoginButNoParticularGroup() {
        Scheduling wp = atSchedule("DWG-118");

        assertThat(loggedInAs("time-method-analyst").get("/api/v1/workpacks/" + wp.number() + "/look-ahead").statusCode()).isEqualTo(200);
        assertThat(new TestBrowser(port).get("/api/v1/workpacks/" + wp.number() + "/look-ahead").statusCode()).isEqualTo(401);
    }

    /** A retried job (same Work Pack, same dates) records once and writes nothing more (D65, 09 S7). */
    @Test
    void retriedScheduleJobRecordsOnce() {
        Scheduling wp = atSchedule("DWG-118");
        assertThat(submit(loggedInAs("production-planner"), wp, "2026-11-03", "2026-11-06").saved()).isTrue();
        int auditRows = scheduleAuditRows(wp);
        JobClient client = Mockito.mock(JobClient.class, Mockito.RETURNS_DEEP_STUBS);
        Map<String, Object> variables = Map.of("completedBy", "production-planner", "plannedStartDate", "2026-11-03", "plannedEndDate", "2026-11-06");

        worker.handle(client, job(91L, wp.instance(), variables));
        worker.handle(client, job(92L, wp.instance(), variables));

        Mockito.verify(client).newCompleteCommand(91L);
        Mockito.verify(client).newCompleteCommand(92L);
        assertThat(scheduleRows(wp)).isEqualTo(1);
        assertThat(scheduleAuditRows(wp)).isEqualTo(auditRows);
    }

    /** The scheduler is the Production Planner (D63): a job without completedBy fails (S7), never records the service user. */
    @Test
    void workerFailsWithoutWhoScheduled() {
        Scheduling wp = atSchedule("DWG-118");
        JobClient client = Mockito.mock(JobClient.class, Mockito.RETURNS_DEEP_STUBS);

        assertThatThrownBy(() -> worker.handle(client, job(95L, wp.instance(), Map.of("plannedStartDate", "2026-11-03", "plannedEndDate", "2026-11-06"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("completedBy");

        Mockito.verify(client, Mockito.never()).newCompleteCommand(Mockito.anyLong());
        assertThat(scheduleRows(wp)).isZero();
    }

    /** Only a Released Work Pack is scheduled (D25, the release gate): a Draft one is a fault, not a business rejection. */
    @Test
    void workerFailsForAWorkPackThatIsNotReleased() {
        Draft draft = draftWorkPack("DWG-118");
        JobClient client = Mockito.mock(JobClient.class, Mockito.RETURNS_DEEP_STUBS);

        assertThatThrownBy(() -> worker.handle(client, job(96L, draft.instance(), Map.of("completedBy", "production-planner",
                "plannedStartDate", "2026-11-03", "plannedEndDate", "2026-11-06"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not Released");

        Mockito.verify(client, Mockito.never()).newCompleteCommand(Mockito.anyLong());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.workpack_schedule WHERE workpack_id = ?", Integer.class, draft.id())).isZero();
    }

    // --- arranging the Given, and putting the seed back ---------------------------------------------------------------------

    record Seed(List<Map<String, Object>> revisions, List<Map<String, Object>> reservations, List<Map<String, Object>> lookAhead) {
    }

    private void touch(String drawing) {
        touched.computeIfAbsent(drawing, d -> new Seed(
                jdbc.queryForList("SELECT * FROM bpa.drawing_revision WHERE drawing_ref = ?", d),
                jdbc.queryForList("SELECT * FROM bpa.material_reservation WHERE drawing_ref = ?", d),
                jdbc.queryForList("SELECT * FROM bpa.lookahead_item WHERE design_dwg_ref = ?", d)));
    }

    private void setLookAhead(String drawing, String shopDrgMark, String assembly, String weldMap) {
        removeLookAhead(drawing);
        jdbc.update("""
                INSERT INTO bpa.lookahead_item (design_dwg_ref, shop_drg_mark_plan_date, assembly_plan_date, weld_map_plan_date, created_by, updated_by)
                VALUES (?, ?::date, ?::date, ?::date, 'test', 'test')""", drawing, shopDrgMark, assembly, weldMap);
    }

    private void removeLookAhead(String drawing) {
        touch(drawing);
        jdbc.update("DELETE FROM bpa.lookahead_item WHERE design_dwg_ref = ?", drawing);
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

    // --- the Background: a Released Work Pack that awaits scheduling -------------------------------------------------------------

    record Scheduling(long instance, UUID id, String number, String scheduleTask) {
        Scheduling withTask(String other) {
            return new Scheduling(instance, id, number, other);
        }
    }

    record Draft(long instance, UUID id, String number, String confirmTask) {
    }

    /** What happened after the Schedule task was completed: the schedule is saved, or the task is back with messages. */
    record Outcome(boolean saved, String taskKey, List<String> messages) {
    }

    /** The Background: a Work Pack for the drawing (MAT-PIPE-06, date to complete 06 Nov 2026), Draft, with its Confirm task open. */
    private Draft draftWorkPack(String drawing) {
        TestBrowser analyst = loggedInAs("time-method-analyst");
        HttpResponse<String> started = analyst.post("/api/v1/workpacks", "");
        assertThat(started.statusCode()).as("POST /api/v1/workpacks: " + started.body()).isEqualTo(201);
        long instance = Long.parseLong(json(started.body()).path("processInstanceKey").asText());
        String create = awaitOpenTask(instance, CREATE).path("userTaskKey").asText();
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("activityRef", "A-2201");
        variables.put("drawingRef", drawing);
        variables.put("phases", List.of(Map.of("phase", "Cutting", "manhours", 150), Map.of("phase", "Welding", "manhours", 250)));
        variables.put("materials", List.of(Map.of("materialRef", "MAT-PIPE-06", "phase", "Cutting")));
        variables.put("departmentRef", "Fabrication");
        variables.put("completionDate", "2026-11-06");
        HttpResponse<String> done = analyst.post("/api/v1/tasks/" + create + "/complete", toJson(Map.of("variables", variables)));
        assertThat(done.statusCode()).as("complete Create: " + done.body()).isEqualTo(204);
        String confirm = awaitOpenTask(instance, CONFIRM).path("userTaskKey").asText();
        Map<String, Object> row = jdbc.queryForMap("SELECT id, workpack_number FROM bpa.workpack WHERE process_instance_key = ?", instance);
        return new Draft(instance, (UUID) row.get("id"), (String) row.get("workpack_number"), confirm);
    }

    /** Create, confirm unchanged, release check Ready, the Project Engineer approves: the process waits at Schedule Work Pack. */
    private Scheduling atSchedule(String drawing) {
        Draft draft = draftWorkPack(drawing);
        TestBrowser analyst = loggedInAs("time-method-analyst");
        HttpResponse<String> open = analyst.get("/api/v1/tasks/" + draft.confirmTask());
        Map<String, Object> variables = JSON.convertValue(json(open.body()).path("variables"), new TypeReference<Map<String, Object>>() {
        });
        HttpResponse<String> confirmed = analyst.post("/api/v1/tasks/" + draft.confirmTask() + "/complete", toJson(Map.of("variables", variables)));
        assertThat(confirmed.statusCode()).as("complete Confirm: " + confirmed.body()).isEqualTo(204);
        String signOff = awaitSignOffTask(draft.instance());
        HttpResponse<String> approved = completeSignOff(loggedInAs("project-engineer"), signOff, "Approved", null);
        assertThat(approved.statusCode()).as("approve: " + approved.body()).isEqualTo(204);
        String schedule = awaitOpenTask(draft.instance(), SCHEDULE).path("userTaskKey").asText();
        return new Scheduling(draft.instance(), draft.id(), draft.number(), schedule);
    }

    private HttpResponse<String> complete(TestBrowser browser, String taskKey, String start, String end) {
        return browser.post("/api/v1/tasks/" + taskKey + "/complete",
                toJson(Map.of("variables", Map.of("plannedStartDate", start, "plannedEndDate", end))));
    }

    /** Completes the Schedule task as the Production Planner; then either the schedule is saved or the task comes back with messages. */
    private Outcome submit(TestBrowser planner, Scheduling wp, String start, String end) {
        HttpResponse<String> done = complete(planner, wp.scheduleTask(), start, end);
        assertThat(done.statusCode()).as("complete Schedule: " + done.body()).isEqualTo(204);
        return poll(() -> {
            if (scheduleRows(wp) > 0) {
                return new Outcome(true, null, List.of());
            }
            List<JsonNode> back = openTasks(wp.instance(), SCHEDULE).stream()
                    .filter(t -> !wp.scheduleTask().equals(t.path("userTaskKey").asText())).toList();
            if (back.isEmpty()) {
                return null;
            }
            String key = back.get(0).path("userTaskKey").asText();
            List<String> messages = new ArrayList<>();
            json(planner.get("/api/v1/tasks/" + key).body()).path("validationMessages").forEach(m -> messages.add(m.asText()));
            return new Outcome(false, key, messages);
        }, o -> o != null, "the schedule of " + wp.number() + " saved or returned");
    }

    private int scheduleRows(Scheduling wp) {
        return jdbc.queryForObject("SELECT count(*) FROM bpa.workpack_schedule WHERE workpack_id = ?", Integer.class, wp.id());
    }

    private Map<String, Object> scheduleRow(Scheduling wp) {
        return jdbc.queryForMap("SELECT * FROM bpa.workpack_schedule WHERE workpack_id = ?", wp.id());
    }

    /** The audit rows the schedule could add: its own, one per business column. */
    private int scheduleAuditRows(Scheduling wp) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM bpa.audit_log a
                WHERE a.table_name = 'workpack_schedule' AND a.record_id IN (SELECT id FROM bpa.workpack_schedule WHERE workpack_id = ?)""",
                Integer.class, wp.id());
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
        Mockito.when(job.getElementId()).thenReturn("ServiceTask_SaveWorkPackSchedule");
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
