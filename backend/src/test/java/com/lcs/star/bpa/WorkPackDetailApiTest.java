package com.lcs.star.bpa;

import static com.lcs.star.bpa.CamundaSteps.json;
import static com.lcs.star.bpa.CamundaSteps.poll;
import static com.lcs.star.bpa.CamundaSteps.toJson;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.lcs.star.bpa.CamundaRest.Reply;
import com.lcs.star.bpa.api.TestBrowser;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
 * BPA-73 (E2-23): the read-only Work Pack detail: {@code GET /api/v1/workpacks/{n}} (summary, Overview, Workers, Hours, Progress), {@code .../history} and
 * {@code .../open-tasks}. One test per Gherkin scenario, named as the React tests are; the extras are ours (plan BPA-73, Questions 1 to 6). The Background
 * is built with SQL on top of {@link BookHoursFixture}: WP-000457 is In Progress with phases Cutting 150 and Welding 300, planned 3 to 6 Nov, E1021 and
 * E1099 assigned (E1099 kept with the flag HoursLoadedNotRemoved), lines of 27.50 h Approved and 4.00 h Rejected, and the week ending 19 Nov 2026 Locked with
 * the Work Pack approved at 35.00 % and its activity's Earned man-hours; WP-000490 is a Draft Work Pack with nothing booked. Today is 20 Nov 2026
 * (Asia/Riyadh). Scenario 7 and the open-task extras run against the real process engine.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(AssignmentFileApiTest.TestClockConfig.class)
class WorkPackDetailApiTest {

    static final Instant TODAY = Instant.parse("2026-11-20T06:00:00Z");
    static final String DETAIL = "/api/v1/workpacks/";
    static final String PROCESS = "Process_PPC_TimesheetToProgress";
    static final String BOOK_HOURS = "UserTask_BookHours";
    static final String VALIDATE = "UserTask_ValidateHours";
    static final String APPROVE_PRODUCTION = "UserTask_ApproveHoursProduction";
    static final List<String> PERSONAS = List.of("time-method-analyst", "project-engineer", "production-planner", "production-team", "ppc-team", "supervisor",
            "workpack-owner", "timekeeper", "production-supervisor", "cost-controller", "project-manager");

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
    AssignmentFileApiTest.TestClock clock;

    private BookHoursFixture fixture;
    private BookHoursFixture.Ids ids;
    private AssignmentFlow flow;
    private UUID wp457;
    /** All test classes share one Camunda: no task of another class, nor of an earlier test of this one, may mix with a test's instances. */
    @BeforeAll
    static void noTaskLeftWaiting() {
        IntegrationApp.background();
        cancelWaiting();
    }

    private static void cancelWaiting() {
        for (String element : new String[] {BOOK_HOURS, VALIDATE, APPROVE_PRODUCTION, "UserTask_ApproveHoursPpc", "UserTask_ApproveCompletion", "UserTask_ScheduleWorkPack"}) {
            CamundaSteps.cancelInstancesWaitingAt(element);
        }
    }

    @BeforeEach
    void background() {
        cancelWaiting();
        clock.set(TODAY);
        jdbc.update("DELETE FROM bpa.workpack_schedule"); // BookHoursFixture.reset() does not clear it, and the foreign key would stop the delete of the Work Packs
        jdbc.update("DELETE FROM bpa.workpack_print");
        jdbc.update("DELETE FROM bpa.notification");
        fixture = new BookHoursFixture(jdbc);
        ids = fixture.reset();
        flow = new AssignmentFlow(port, jdbc);
        new RevisionFlow(port, jdbc).seedAppUsers();
        AssignmentFlow.assignPersonasTheSeededProjects(jdbc); // BPA-79: the detail is open only to a user with the Work Pack's project
        wp457 = ids.workpackId();
        jdbc.update("UPDATE bpa.workpack SET status = 'In Progress' WHERE id = ?", wp457);
        jdbc.update("UPDATE bpa.workpack_phase SET manhours = 150 WHERE workpack_id = ? AND phase = 'Cutting'", wp457);
        jdbc.update("UPDATE bpa.workpack_phase SET manhours = 300 WHERE workpack_id = ? AND phase = 'Welding'", wp457);
        jdbc.update("""
                INSERT INTO bpa.workpack_schedule (workpack_id, planned_start_date, planned_end_date, scheduled_by, created_by, updated_by)
                VALUES (?, DATE '2026-11-03', DATE '2026-11-06', 'production-planner', 'test', 'test')""", wp457);
        jdbc.update("""
                UPDATE bpa.worker_assignment SET flag = 'HoursLoadedNotRemoved'
                WHERE workpack_id = ? AND worker_id = (SELECT id FROM bpa.worker WHERE employee_ref = 'E1099')""", wp457);
        // 27.50 h Approved (E1021 8 + 9.5, E1099 10) and 4.00 h Rejected (E1099)
        fixture.line(ids.timesheet("E1021"), LocalDate.of(2026, 11, 3), "Cutting", new BigDecimal("8"), "Approved");
        fixture.line(ids.timesheet("E1021"), LocalDate.of(2026, 11, 4), "Cutting", new BigDecimal("9.5"), "Approved");
        fixture.line(ids.timesheet("E1099"), LocalDate.of(2026, 11, 3), "Welding", new BigDecimal("10"), "Approved");
        fixture.line(ids.timesheet("E1099"), LocalDate.of(2026, 11, 5), "Welding", new BigDecimal("4"), "Rejected");
        // the week ending 19 Nov 2026 is Locked with WP-000457 at 35.00 %; the activity's Earned man-hours that week
        UUID week = period("2026-11-19", "Locked", 9000000019L);
        progress(wp457, week, "35.00");
        jdbc.update("""
                INSERT INTO bpa.activity_progress (activity_ref, progress_period_id, actual_pct, budgeted_units, earned_manhours, created_by, updated_by)
                VALUES ('A-2201', ?, 35.00, 500, 175.00, 'test', 'test')""", week);
        draftWorkPack490();
    }

    // --- the scenarios (the backend part of each) --------------------------------------------------------------------------------

    @Test
    void BPA73_overviewShowsStatusAndPhases() {
        HttpResponse<String> response = flow.loggedInAs("ppc-team").get(DETAIL + "WP-000457");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode detail = json(response.body());
        assertThat(detail.path("summary").path("workpackNumber").asText()).isEqualTo("WP-000457");
        assertThat(detail.path("summary").path("status").asText()).isEqualTo("In Progress");
        assertThat(detail.path("summary").path("plannedStartDate").asText()).isEqualTo("2026-11-03");
        assertThat(detail.path("summary").path("plannedEndDate").asText()).isEqualTo("2026-11-06");
        List<String> phases = new ArrayList<>();
        detail.path("overview").path("phases").forEach(p -> phases.add(p.path("phase").asText() + " " + p.path("manhours").decimalValue().stripTrailingZeros().toPlainString()));
        assertThat(phases).containsExactlyInAnyOrder("Cutting 150", "Welding 300");
        assertThat(detail.path("overview").path("schedule").path("plannedStartDate").asText()).isEqualTo("2026-11-03");
        assertThat(detail.path("overview").path("schedule").path("plannedEndDate").asText()).isEqualTo("2026-11-06");
        assertThat(detail.path("overview").path("materials").isArray()).isTrue();
    }

    @Test
    void BPA73_workersTabShowsKeptWorker() {
        JsonNode detail = json(flow.loggedInAs("ppc-team").get(DETAIL + "WP-000457").body());

        Map<String, Boolean> flagged = new LinkedHashMap<>();
        detail.path("workers").forEach(w -> flagged.put(w.path("badgeNo").asText(), w.path("hoursLoadedNotRemoved").asBoolean()));
        assertThat(flagged).containsEntry("E1099", true).containsEntry("E1021", false);
    }

    @Test
    void BPA73_hoursTabExcludesRejectedFromTotal() {
        JsonNode hours = json(flow.loggedInAs("ppc-team").get(DETAIL + "WP-000457").body()).path("hours");

        List<String> statuses = new ArrayList<>();
        hours.path("lines").forEach(l -> statuses.add(l.path("badgeNo").asText() + " " + l.path("status").asText()));
        assertThat(statuses).containsExactlyInAnyOrder("E1021 Approved", "E1021 Approved", "E1099 Approved", "E1099 Rejected");
        assertThat(hours.path("totalHours").decimalValue()).as("the Rejected 4.00 h is not counted").isEqualByComparingTo("27.50");
    }

    @Test
    void BPA73_progressTabShowsLockedWeek() {
        JsonNode progress = json(flow.loggedInAs("ppc-team").get(DETAIL + "WP-000457").body()).path("progress");

        assertThat(progress).hasSize(1);
        JsonNode week = progress.get(0);
        assertThat(week.path("weekEnding").asText()).isEqualTo("2026-11-19");
        assertThat(week.path("periodStatus").asText()).isEqualTo("Locked");
        assertThat(week.path("approvedPct").decimalValue()).isEqualByComparingTo("35.00");
        assertThat(week.path("earnedManhours").decimalValue()).as("the Earned man-hours of the activity that week").isEqualByComparingTo("175.00");
    }

    @Test
    void BPA73_draftWorkPackHasNothingYet() {
        HttpResponse<String> response = flow.loggedInAs("ppc-team").get(DETAIL + "WP-000490");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode detail = json(response.body());
        assertThat(detail.path("summary").path("status").asText()).isEqualTo("Draft");
        assertThat(detail.path("hours").path("lines")).isEmpty();
        assertThat(detail.path("hours").path("totalHours").decimalValue()).isEqualByComparingTo("0");
        assertThat(detail.path("progress")).isEmpty();
        assertThat(detail.path("overview").path("schedule").isMissingNode()).as("not scheduled yet").isTrue();
    }

    @Test
    void BPA73_unknownWorkPack() {
        TestBrowser ppc = flow.loggedInAs("ppc-team");

        for (String path : new String[] {"", "/history", "/open-tasks"}) {
            HttpResponse<String> response = ppc.get(DETAIL + "WP-999999" + path);
            assertThat(response.statusCode()).as("GET " + path + ": " + response.body()).isEqualTo(404);
            assertThat(json(response.body()).path("detail").asText()).isEqualTo("Work Pack not found");
        }
    }

    @Test
    void BPA73_openTaskOnlyForOwnGroup() {
        long instance = openApproveHoursProductionTask();

        // timekeeper: the task is listed but cannot be opened
        HttpResponse<String> asTimekeeper = flow.loggedInAs("timekeeper").get(DETAIL + "WP-000457/open-tasks");
        assertThat(asTimekeeper.statusCode()).as(asTimekeeper.body()).isEqualTo(200);
        JsonNode task = taskOf(json(asTimekeeper.body()), APPROVE_PRODUCTION);
        assertThat(task.path("canOpen").asBoolean()).isFalse();
        assertThat(task.path("candidateGroups").toString()).contains("production-supervisor");
        assertThat(task.has("variables")).as("no task data").isFalse();
        HttpResponse<String> forbidden = flow.loggedInAs("timekeeper").get("/api/v1/tasks/" + task.path("key").asText());
        assertThat(forbidden.statusCode()).as("the task itself still refuses another group").isEqualTo(403);

        // production-supervisor: the same task, openable
        JsonNode own = taskOf(json(flow.loggedInAs("production-supervisor").get(DETAIL + "WP-000457/open-tasks").body()), APPROVE_PRODUCTION);
        assertThat(own.path("canOpen").asBoolean()).isTrue();
        assertThat(own.path("key").asText()).isEqualTo(task.path("key").asText());
        assertThat(instance).isPositive();
    }

    // --- not scenarios (plan BPA-73, Questions 1 to 6) -----------------------------------------------------------------------------

    @Test
    void aSubmittedLineShowsTheStepItWaitsAt() {
        UUID submission = jdbc.queryForObject("""
                INSERT INTO bpa.hours_submission (workpack_id, process_instance_key, round_no, submitted_by, submitted_at, stage, created_by, updated_by)
                VALUES (?, 9000000401, 1, 'supervisor', now(), 'Ppc', 'test', 'test') RETURNING id""", UUID.class, wp457);
        UUID line = fixture.line(ids.timesheet("E1021"), LocalDate.of(2026, 11, 6), "Cutting", new BigDecimal("6"), "Submitted");
        jdbc.update("UPDATE bpa.digital_timesheet_line SET hours_submission_id = ? WHERE id = ?", submission, line);

        JsonNode lines = json(flow.loggedInAs("ppc-team").get(DETAIL + "WP-000457").body()).path("hours").path("lines");

        JsonNode submitted = null;
        for (JsonNode l : lines) {
            if ("Submitted".equals(l.path("status").asText())) {
                submitted = l;
            }
        }
        assertThat(submitted).isNotNull();
        assertThat(submitted.path("step").asText()).isEqualTo("Ppc");
        assertThat(lines.get(0).path("step").isMissingNode()).as("an Approved line has no step").isTrue();
    }

    @Test
    void aRetractionIsShownWithItsWeek() {
        UUID week = jdbc.queryForObject("SELECT id FROM bpa.progress_period WHERE week_ending = DATE '2026-11-19'", UUID.class);
        UUID row = jdbc.queryForObject("SELECT id FROM bpa.workpack_progress WHERE workpack_id = ? AND progress_period_id = ?", UUID.class, wp457, week);
        jdbc.update("""
                INSERT INTO bpa.progress_retraction (workpack_progress_id, requested_by, reason, approved_pct, decision, decided_by, decided_at, created_by, updated_by)
                VALUES (?, 'ppc-team', 'Punch list items open', 35.00, 'Approved', 'project-manager', now(), 'test', 'test')""", row);

        JsonNode retractions = json(flow.loggedInAs("ppc-team").get(DETAIL + "WP-000457").body()).path("progress").get(0).path("retractions");

        assertThat(retractions).hasSize(1);
        assertThat(retractions.get(0).path("reason").asText()).isEqualTo("Punch list items open");
        assertThat(retractions.get(0).path("decision").asText()).isEqualTo("Approved");
    }

    @Test
    void historyPagesNewestFirstAndKeepsToTheWorkPack() {
        UUID phase = jdbc.queryForObject("SELECT id FROM bpa.workpack_phase WHERE workpack_id = ? AND phase = 'Cutting'", UUID.class, wp457);
        UUID other = jdbc.queryForObject("SELECT id FROM bpa.workpack WHERE workpack_number = 'WP-000490'", UUID.class);
        audit("workpack", wp457, "status", "Released", "In Progress", "2026-11-03T08:00:00Z");
        audit("workpack_phase", phase, "manhours", "100", "150", "2026-11-04T08:00:00Z");
        audit("workpack", other, "status", "Draft", "Released", "2026-11-05T08:00:00Z"); // another Work Pack: not in this history
        jdbc.update("""
                INSERT INTO bpa.notification (workpack_id, drawing_ref, revision, recipient_username, channel, sent_at, created_by, updated_by)
                VALUES (?, 'DWG-118', 'B', 'workpack-owner', 'InSystem', TIMESTAMPTZ '2026-11-06 08:00:00+00', 'test', 'test')""", wp457);

        JsonNode first = json(flow.loggedInAs("ppc-team").get(DETAIL + "WP-000457/history?page=0&size=2").body());
        JsonNode second = json(flow.loggedInAs("ppc-team").get(DETAIL + "WP-000457/history?page=1&size=2").body());

        assertThat(first.path("total").asInt()).as("the Work Pack's row, its phase's row and the notice").isEqualTo(3);
        assertThat(first.path("items")).hasSize(2);
        assertThat(first.path("items").get(0).path("kind").asText()).isEqualTo("Notice");
        assertThat(first.path("items").get(0).path("notice").path("drawingRef").asText()).isEqualTo("DWG-118");
        assertThat(first.path("items").get(1).path("tableName").asText()).isEqualTo("workpack_phase");
        assertThat(second.path("items")).hasSize(1);
        assertThat(second.path("items").get(0).path("tableName").asText()).isEqualTo("workpack");
        assertThat(second.path("items").get(0).path("newValue").asText()).isEqualTo("In Progress");
    }

    @Test
    void anHoursReminderIsNotAHistoryNotice() {
        // BPA-74 review: a reminder row (kind HoursReminder, no drawing) must not appear in the drawing-notice history of BPA-73
        audit("workpack", wp457, "status", "Released", "In Progress", "2026-11-03T08:00:00Z");
        jdbc.update("""
                INSERT INTO bpa.notification (workpack_id, kind, message, reminder_key, recipient_username, channel, sent_at, created_by, updated_by)
                VALUES (?, 'HoursReminder', 'Hours for WP-000457 are waiting for your decision since 17 Nov 2026', '1:Timekeeper:2', 'workpack-owner', 'InSystem',
                        TIMESTAMPTZ '2026-11-19 08:00:00+00', 'test', 'test')""", wp457);

        JsonNode history = json(flow.loggedInAs("ppc-team").get(DETAIL + "WP-000457/history").body());

        assertThat(history.path("total").asInt()).as("only the change; a reminder is not a drawing notice").isEqualTo(1);
        history.path("items").forEach(i -> assertThat(i.path("kind").asText()).isEqualTo("Change"));
    }

    @Test
    void anOpenWeeklyTaskIsListedForAWorkPackInThatWeek() {
        // Q1 (ASSUMPTION): the weekly process of a week that is not Locked, in which the Work Pack has a progress row
        UUID week = period("2026-11-26", "CutOff", 9000000026L);
        progress(wp457, week, "40.00");
        Reply started = IntegrationApp.admin.post("/v2/process-instances", Map.of("processDefinitionId", "Process_PPC_WeeklyProgressPeriod",
                "variables", Map.of("progressPeriodId", week.toString(), "weekEnding", "2026-11-26"),
                "startInstructions", List.of(Map.of("elementId", "UserTask_ApproveCompletion"))));
        assertThat(started.status()).as(started.body().toString()).isEqualTo(200);
        long instance = Long.parseLong(started.body().path("processInstanceKey").asText());
        jdbc.update("UPDATE bpa.progress_period SET process_instance_key = ? WHERE id = ?", instance, week);
        CamundaSteps.awaitOpenTask(instance, "UserTask_ApproveCompletion");

        JsonNode task = taskOf(json(flow.loggedInAs("timekeeper").get(DETAIL + "WP-000457/open-tasks").body()), "UserTask_ApproveCompletion");

        assertThat(task.path("canOpen").asBoolean()).as("timekeeper is not ppc-team").isFalse();
        assertThat(taskOf(json(flow.loggedInAs("ppc-team").get(DETAIL + "WP-000457/open-tasks").body()), "UserTask_ApproveCompletion").path("canOpen").asBoolean()).isTrue();
        assertThat(elementsOf(json(flow.loggedInAs("ppc-team").get(DETAIL + "WP-000490/open-tasks").body()))).as("WP-000490 has no progress row in that week").doesNotContain("UserTask_ApproveCompletion");
    }

    @Test
    void historyPagesAreStableWhenRowsShareATimestamp() {
        // QA review: audit rows written together share changed_at, table and column and differ only by record; LIMIT/OFFSET must not repeat or skip one
        List<UUID> records = jdbc.queryForList("SELECT id FROM bpa.workpack_phase WHERE workpack_id = ?", UUID.class, wp457);
        assertThat(records).hasSize(2);
        audit("workpack_phase", records.get(0), "manhours", "1", "a", "2026-11-04T08:00:00Z");
        audit("workpack_phase", records.get(1), "manhours", "2", "b", "2026-11-04T08:00:00Z");
        audit("workpack_phase", records.get(0), "manhours", "3", "c", "2026-11-04T08:00:00Z");
        List<String> seen = new ArrayList<>();

        for (int page = 0; page < 3; page++) {
            JsonNode items = json(flow.loggedInAs("ppc-team").get(DETAIL + "WP-000457/history?page=" + page + "&size=1").body()).path("items");
            assertThat(items).hasSize(1);
            seen.add(items.get(0).path("newValue").asText());
        }

        assertThat(seen).as("every row once, over the three pages").containsExactlyInAnyOrder("a", "b", "c");
    }

    @Test
    void historyIncludesTheRecordsOfTheWorkPacksChildTables() {
        UUID line = jdbc.queryForObject("SELECT id FROM bpa.digital_timesheet_line WHERE status = 'Rejected'", UUID.class);
        UUID assignment = jdbc.queryForObject("SELECT id FROM bpa.worker_assignment WHERE workpack_id = ? LIMIT 1", UUID.class, wp457);
        UUID progress = jdbc.queryForObject("SELECT id FROM bpa.workpack_progress WHERE workpack_id = ?", UUID.class, wp457);
        audit("digital_timesheet_line", line, "status", "Submitted", "Rejected", "2026-11-05T08:00:00Z");
        audit("worker_assignment", assignment, "flag", null, "HoursLoadedNotRemoved", "2026-11-04T08:00:00Z");
        audit("workpack_progress", progress, "approved_pct", null, "35.00", "2026-11-03T08:00:00Z");

        JsonNode history = json(flow.loggedInAs("ppc-team").get(DETAIL + "WP-000457/history").body());

        List<String> tables = new ArrayList<>();
        history.path("items").forEach(i -> tables.add(i.path("tableName").asText()));
        assertThat(history.path("total").asInt()).isEqualTo(3);
        assertThat(tables).containsExactly("digital_timesheet_line", "worker_assignment", "workpack_progress");
    }

    @Test
    void anInvalidPageOrSizeIs400() {
        for (String query : new String[] {"?size=0", "?size=201", "?page=-1"}) {
            HttpResponse<String> response = flow.loggedInAs("ppc-team").get(DETAIL + "WP-000457/history" + query);
            assertThat(response.statusCode()).as(query + ": " + response.body()).isEqualTo(400);
            assertThat(json(response.body()).path("detail").asText()).isEqualTo("page must be 0 or more and size 1 to 200");
        }
    }

    @Test
    void anotherWorkPacksTimesheetTaskIsNotListed() {
        // a time sheet process of WP-000490 is waiting at Book hours; WP-000457 has no task of its own, so only WP-000490 lists it
        UUID draft = jdbc.queryForObject("SELECT id FROM bpa.workpack WHERE workpack_number = 'WP-000490'", UUID.class);
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("workpackId", draft.toString());
        variables.put("workpackNumber", "WP-000490");
        Reply started = IntegrationApp.admin.post("/v2/process-instances", Map.of(
                "processDefinitionId", PROCESS, "variables", variables, "startInstructions", List.of(Map.of("elementId", BOOK_HOURS))));
        assertThat(started.status()).as(started.body().toString()).isEqualTo(200);
        CamundaSteps.awaitOpenTask(Long.parseLong(started.body().path("processInstanceKey").asText()), BOOK_HOURS);

        assertThat(elementsOf(json(flow.loggedInAs("supervisor").get(DETAIL + "WP-000457/open-tasks").body()))).as("WP-000490 task is not WP-000457 task").doesNotContain(BOOK_HOURS);
        assertThat(taskOf(json(flow.loggedInAs("supervisor").get(DETAIL + "WP-000490/open-tasks").body()), BOOK_HOURS).path("canOpen").asBoolean()).isTrue();
    }

    @Test
    void anEpic1TaskIsListedForItsWorkPack() {
        // Q1 (a): the Work Pack's own assignment instance, here waiting at Schedule Work Pack
        Reply started = IntegrationApp.admin.post("/v2/process-instances", Map.of(
                "processDefinitionId", "Process_PPC_WorkPackAssignment", "startInstructions", List.of(Map.of("elementId", "UserTask_ScheduleWorkPack"))));
        assertThat(started.status()).as(started.body().toString()).isEqualTo(200);
        long instance = Long.parseLong(started.body().path("processInstanceKey").asText());
        jdbc.update("UPDATE bpa.workpack SET process_instance_key = ? WHERE id = ?", instance, wp457);
        CamundaSteps.awaitOpenTask(instance, "UserTask_ScheduleWorkPack");

        JsonNode task = taskOf(json(flow.loggedInAs("production-planner").get(DETAIL + "WP-000457/open-tasks").body()), "UserTask_ScheduleWorkPack");

        assertThat(task.path("canOpen").asBoolean()).isTrue();
        assertThat(taskOf(json(flow.loggedInAs("ppc-team").get(DETAIL + "WP-000457/open-tasks").body()), "UserTask_ScheduleWorkPack").path("canOpen").asBoolean()).isFalse();
    }

    @Test
    void everyPersonaReadsTheDetail() {
        for (String persona : PERSONAS) {
            HttpResponse<String> response = flow.loggedInAs(persona).get(DETAIL + "WP-000457");
            assertThat(response.statusCode()).as(persona + ": " + response.body()).isEqualTo(200);
        }
    }

    @Test
    void notLoggedInIs401() {
        assertThat(new TestBrowser(port).get(DETAIL + "WP-000457").statusCode()).isEqualTo(401);
        assertThat(new TestBrowser(port).get(DETAIL + "WP-000457/history").statusCode()).isEqualTo(401);
        assertThat(new TestBrowser(port).get(DETAIL + "WP-000457/open-tasks").statusCode()).isEqualTo(401);
    }

    @Test
    void aMalformedNumberIs400() {
        assertThat(flow.loggedInAs("ppc-team").get(DETAIL + "WP-12").statusCode()).isEqualTo(400);
    }

    // --- the Background ---------------------------------------------------------------------------------------------------------

    /** WP-000490: a Draft Work Pack (no schedule, no assignment, no hours, no progress), as the Gherkin Given says. */
    private void draftWorkPack490() {
        UUID draft = jdbc.queryForObject("""
                INSERT INTO bpa.workpack (workpack_number, activity_ref, drawing_ref, department_ref, completion_date, status, process_instance_key, created_by, updated_by)
                VALUES ('WP-000490', 'A-2201', 'DWG-118', 'Fabrication', DATE '2026-12-31', 'Draft', 9000000090, 'test', 'test') RETURNING id""", UUID.class);
        jdbc.update("INSERT INTO bpa.workpack_phase (workpack_id, phase, manhours, created_by, updated_by) VALUES (?, 'Cutting', 80, 'test', 'test')", draft);
    }

    private UUID period(String weekEnding, String status, long processInstanceKey) {
        jdbc.update("""
                INSERT INTO bpa.progress_period (week_ending, cut_off_at, status, locked_at, process_instance_key, created_by, updated_by)
                VALUES (?::date, ?::date + TIME '20:59' AT TIME ZONE 'UTC', ?, CASE WHEN ? = 'Locked' THEN now() END, ?, 'test', 'test')""",
                weekEnding, weekEnding, status, status, processInstanceKey);
        return jdbc.queryForObject("SELECT id FROM bpa.progress_period WHERE week_ending = ?::date", UUID.class, weekEnding);
    }

    private void progress(UUID workpack, UUID period, String approvedPct) {
        jdbc.update("""
                INSERT INTO bpa.workpack_progress (workpack_id, progress_period_id, approved_pct, approved_by, approved_at, created_by, updated_by)
                VALUES (?, ?, ?::numeric, 'ppc-team', now(), 'test', 'test')""", workpack, period, approvedPct);
    }

    private void audit(String table, UUID record, String column, String oldValue, String newValue, String at) {
        jdbc.update("""
                INSERT INTO bpa.audit_log (table_name, record_id, column_name, old_value, new_value, username, changed_at)
                VALUES (?, ?, ?, ?, ?, 'test', ?::timestamptz)""", table, record, column, oldValue, newValue, at);
    }

    /** A real Approve hours (Production) task on WP-000457: a Draft line is sent by the supervisor and validated by the timekeeper (the BPA-53 and BPA-56 steps). */
    private long openApproveHoursProductionTask() {
        fixture.line(ids.timesheet("E1021"), LocalDate.of(2026, 11, 18), "Cutting", new BigDecimal("5"), "Draft");
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("workpackId", wp457.toString());
        variables.put("workpackNumber", BookHoursFixture.WORKPACK);
        Reply started = IntegrationApp.admin.post("/v2/process-instances", Map.of(
                "processDefinitionId", PROCESS, "variables", variables, "startInstructions", List.of(Map.of("elementId", BOOK_HOURS))));
        assertThat(started.status()).as("start at " + BOOK_HOURS + ": " + started.body()).isEqualTo(200);
        long instance = Long.parseLong(started.body().path("processInstanceKey").asText());
        String book = CamundaSteps.awaitOpenTask(instance, BOOK_HOURS).path("userTaskKey").asText();
        assertThat(flow.loggedInAs("supervisor").post("/api/v1/tasks/" + book + "/complete", toJson(Map.of("variables", Map.of()))).statusCode()).isEqualTo(204);
        String validate = poll(() -> CamundaSteps.openTasks(instance, VALIDATE).stream().findFirst(), found -> found.isPresent(), VALIDATE + " of " + instance).orElseThrow()
                .path("userTaskKey").asText();
        assertThat(flow.loggedInAs("timekeeper").post("/api/v1/tasks/" + validate + "/complete", toJson(Map.of("variables", Map.of("hoursDecision", "Validated")))).statusCode())
                .isEqualTo(204);
        CamundaSteps.awaitOpenTask(instance, APPROVE_PRODUCTION);
        return instance;
    }

    private static List<String> elementsOf(JsonNode tasks) {
        List<String> elements = new ArrayList<>();
        tasks.forEach(t -> elements.add(t.path("elementId").asText()));
        return elements;
    }

    private static JsonNode taskOf(JsonNode tasks, String elementId) {
        for (JsonNode task : tasks) {
            if (elementId.equals(task.path("elementId").asText())) {
                return task;
            }
        }
        throw new AssertionError(elementId + " is not among the open tasks: " + tasks);
    }
}
