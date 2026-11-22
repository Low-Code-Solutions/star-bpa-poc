package com.lcs.star.bpa;

import static com.lcs.star.bpa.CamundaSteps.activeElements;
import static com.lcs.star.bpa.CamundaSteps.completed;
import static com.lcs.star.bpa.CamundaSteps.json;
import static com.lcs.star.bpa.CamundaSteps.openTasks;
import static com.lcs.star.bpa.CamundaSteps.poll;
import static com.lcs.star.bpa.CamundaSteps.toJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.lcs.star.bpa.CamundaRest.Reply;
import com.lcs.star.bpa.persistence.ProgressPeriodRepository.Period;
import com.lcs.star.bpa.progress.CompletionApprovalService;
import com.lcs.star.bpa.progress.CompletionApprovalService.Approval;
import com.lcs.star.bpa.progress.CompletionProgressService;
import com.lcs.star.bpa.progress.ProgressCheckService;
import com.lcs.star.bpa.worker.CompleteWorkPacksWorker;
import com.lcs.star.bpa.worker.LockProgressPeriodWorker;
import com.lcs.star.bpa.worker.OpenProgressPeriodWorker;
import com.lcs.star.bpa.worker.ReadCompletionReportWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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
 * BPA-67 (E2-16), the week: the cut-off, the completion of the Work Packs at 100% and the lock. One test per Gherkin scenario, named as in the Jira Definition of Done
 * (the hours scenarios are in {@link HoursLockApiTest}). Background: the business clock is Thursday 19 Nov 2026 23:59 Asia/Riyadh; for the week ending 19 Nov 2026 PPC
 * approved WP-000457 and WP-000461 (last locked week, 12 Nov: 30% and 60%). The weekly instance is started at the step under test and the jobs run in the app; the
 * Work Pack's time sheet instance (Process_PPC_TimesheetToProgress) is started at UserTask_BookHours as BPA-57's tests do. No real waiting: the clock is set, the instance is
 * started at its element, and a retry is the worker's handle called again with a mocked job.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(AssignmentFileApiTest.TestClockConfig.class)
class WeekLockApiTest {

    static final Instant CUT_OFF = Instant.parse("2026-11-19T20:59:00Z"); // Thursday 23:59 in Riyadh
    static final Instant TODAY = Instant.parse("2026-11-20T07:00:00Z"); // 10:00 in Riyadh
    static final String WEEKLY = "Process_PPC_WeeklyProgressPeriod";
    static final String TIMESHEET = "Process_PPC_TimesheetToProgress";
    static final String OPEN_PERIOD = "ServiceTask_OpenProgressPeriod";
    static final String COMPLETE_WORKPACKS = "ServiceTask_CompleteWorkPacks";
    static final String APPROVE_WEEK = "UserTask_ApproveWeeklyProgress";
    static final String COMPLETION = "UserTask_ApproveCompletion";
    static final String BOOK_HOURS = "UserTask_BookHours";
    static final String VALIDATE = "UserTask_ValidateHours";
    static final String APPROVE_PRODUCTION = "UserTask_ApproveHoursProduction";
    static final String APPROVE_PPC = "UserTask_ApproveHoursPpc";
    static final String PERIOD_LOCKED = "EndEvent_PeriodLocked";
    static final String TIMESHEETS_CLOSED = "EndEvent_TimesheetsClosed";
    static final LocalDate W12 = LocalDate.of(2026, 11, 12);
    static final LocalDate W19 = LocalDate.of(2026, 11, 19);
    static final LocalDate D16 = LocalDate.of(2026, 11, 16);
    static final String LOCKED = "Week ending 19 Nov 2026 is locked";

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

    @Autowired
    OpenProgressPeriodWorker openWorker;

    @Autowired
    CompleteWorkPacksWorker completeWorker;

    @Autowired
    LockProgressPeriodWorker lockWorker;

    @Autowired
    ReadCompletionReportWorker readWorker;

    @Autowired
    CompletionApprovalService approvals;

    @Autowired
    CompletionProgressService completionProgress;

    @Autowired
    ProgressCheckService progressChecks;

    private AssignmentFlow flow;
    private BookHoursFixture fixture;
    private UUID period;
    private long weekly;
    /** Work Pack 461 under a number of its own for each test: WorkPackCompleted is buffered for P7D with the number as message id and correlation key (ADR 0008), so a message of an earlier test would otherwise reach this test's time sheet instance. */
    private String wp461;

    /** All test classes share one Camunda: no instance left waiting by another class may take this class's jobs. */
    @BeforeAll
    static void noInstanceLeftWaiting() {
        IntegrationApp.background();
        cancelWaiting();
    }

    private static void cancelWaiting() {
        for (String element : new String[] {OPEN_PERIOD, "ServiceTask_GenerateFmsHoursFile", "UserTask_SendHoursToFms", COMPLETION, "ServiceTask_RecordCompletionApproval", APPROVE_WEEK,
                "ServiceTask_RecordWeekDecision", COMPLETE_WORKPACKS, "ServiceTask_LockProgressPeriod", BOOK_HOURS, VALIDATE, APPROVE_PRODUCTION, APPROVE_PPC}) {
            CamundaSteps.cancelInstancesWaitingAt(element);
        }
    }

    @BeforeEach
    void background() {
        cancelWaiting();
        clock.set(TODAY);
        fixture = new BookHoursFixture(jdbc);
        BookHoursFixture.Ids ids = fixture.reset();
        fixture.completionBackground(); // WP-000457 and WP-000461 In Progress (A-2201), WP-000480 Released
        fixture.fmsBackground(ids); // WP-000470 In Progress
        wp461 = "WP-9" + String.format("%05d", java.util.concurrent.ThreadLocalRandom.current().nextInt(100000));
        jdbc.update("UPDATE bpa.workpack SET workpack_number = ? WHERE workpack_number = 'WP-000461'", wp461);
        flow = new AssignmentFlow(port, jdbc);
        // the last locked week (12 Nov): approved 30 / 60
        jdbc.update("UPDATE bpa.progress_period SET status = 'Locked', locked_at = now() WHERE week_ending = ?", java.sql.Date.valueOf(W12));
        UUID locked = jdbc.queryForObject("SELECT id FROM bpa.progress_period WHERE week_ending = ?", UUID.class, java.sql.Date.valueOf(W12));
        approved(locked, "WP-000457", "30");
        approved(locked, wp461, "60");
        period = null;
    }

    // --- the scenarios ----------------------------------------------------------------------------------------------------

    /** The weekly timer fires at Thursday 23:59 Riyadh; the test starts the instance at its first step with the clock at that moment (the cron itself is not waited for). */
    @Test
    void BPA67_cutOffOpensThePeriod() {
        clock.set(CUT_OFF);

        startAt(OPEN_PERIOD, Map.of());

        UUID opened = poll(() -> jdbc.queryForList("SELECT id FROM bpa.progress_period WHERE week_ending = ?", UUID.class, java.sql.Date.valueOf(W19)).stream().findFirst(),
                found -> found.isPresent(), "the period for the week ending 19 Nov 2026").orElseThrow();
        assertThat(jdbc.queryForObject("SELECT status FROM bpa.progress_period WHERE id = ?", String.class, opened)).isEqualTo("CutOff");
        UUID e1021 = jdbc.queryForObject("SELECT id FROM bpa.digital_timesheet WHERE worker_id = (SELECT id FROM bpa.worker WHERE employee_ref = 'E1021') AND workpack_id = ?",
                UUID.class, workpackId("WP-000457"));
        HttpResponse<String> refused = flow.loggedInAs("supervisor").post("/api/v1/digital-timesheets/" + e1021 + "/lines",
                toJson(Map.of("workDate", "2026-11-19", "phase", "Cutting", "hours", new BigDecimal("2"))));
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(400);
        assertThat(json(refused.body()).path("detail").asText()).isEqualTo("Week ending 19 Nov 2026 is closed");
    }

    @Test
    void BPA67_onePeriodPerWeekOnRetry() {
        clock.set(CUT_OFF);
        ActivatedJob job = job(7_000_001L);
        JobClient client = mock(JobClient.class, RETURNS_DEEP_STUBS);

        openWorker.handle(client, job);
        openWorker.handle(client, job);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.progress_period WHERE week_ending = ?", Integer.class, java.sql.Date.valueOf(W19))).isEqualTo(1);
    }

    @Test
    void BPA67_hundredPercentCompletesTheWorkPack() {
        approvedWeek("100", "40");
        long timesheets = timesheetInstanceAtBookHours(wp461);

        weekly = startWeekly(COMPLETE_WORKPACKS);

        poll(() -> workPackStatus(wp461), "Completed"::equals, "WP-000461 Completed");
        poll(() -> completed(timesheets, TIMESHEETS_CLOSED, 1), done -> done, "the time sheet instance of WP-000461 ended at " + TIMESHEETS_CLOSED);
        assertThat(workPackStatus("WP-000457")).as("40% stays In Progress").isEqualTo("In Progress");
    }

    /** ASSUMPTION (Jira): the message waits, with its time to live, until the instance is back at UserTask_BookHours. */
    @Test
    void BPA67_completionWaitsForApprovalInProgress() {
        approvedWeek("100", "40");
        long timesheets = timesheetInstanceAtPpcApproval(wp461);

        weekly = startWeekly(COMPLETE_WORKPACKS);
        poll(() -> workPackStatus(wp461), "Completed"::equals, "WP-000461 Completed");
        poll(() -> completed(weekly, PERIOD_LOCKED, 1), done -> done, "the weekly instance ended at " + PERIOD_LOCKED);

        assertThat(openTasks(timesheets, APPROVE_PPC)).as("the instance still waits for PPC").hasSize(1);
        assertThat(completed(timesheets, TIMESHEETS_CLOSED, 1)).as("the message waits while the hours are in approval").isFalse();
        HttpResponse<String> decided = decide("ppc-team", awaitTask(timesheets, APPROVE_PPC), "Approved");
        assertThat(decided.statusCode()).as(decided.body()).isEqualTo(204);
        poll(() -> completed(timesheets, TIMESHEETS_CLOSED, 1), done -> done, "the time sheet instance ended at " + TIMESHEETS_CLOSED + " once PPC decided");
    }

    @Test
    void BPA67_belowHundredStaysInProgress() {
        approvedWeek("40", "99.99");

        weekly = startWeekly(COMPLETE_WORKPACKS);

        poll(() -> completed(weekly, PERIOD_LOCKED, 1), done -> done, "the weekly instance ended at " + PERIOD_LOCKED);
        assertThat(workPackStatus("WP-000457")).isEqualTo("In Progress");
        assertThat(workPackStatus(wp461)).isEqualTo("In Progress");
    }

    @Test
    void BPA67_lockFreezesTheWeek() {
        approvedWeek("100", "40");

        weekly = startWeekly(COMPLETE_WORKPACKS);

        poll(() -> jdbc.queryForObject("SELECT status FROM bpa.progress_period WHERE id = ?", String.class, period), "Locked"::equals, "the period Locked");
        assertThat(jdbc.queryForObject("SELECT locked_at FROM bpa.progress_period WHERE id = ?", Timestamp.class, period)).isEqualTo(Timestamp.from(TODAY));
        assertThat(poll(() -> completed(weekly, PERIOD_LOCKED, 1), done -> done, "the weekly instance ended at " + PERIOD_LOCKED)).isTrue();
        assertThatThrownBy(() -> approvals.record(new Period(period, W19), List.of(new Approval("WP-000457", new BigDecimal("50"), "PPC changed it", null)), "ppc-team"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(LOCKED);
        assertThat(approvedPct("WP-000457")).as("the approved % did not change").isEqualByComparingTo("40");
    }

    /** Decision (b) of the story: a week sent back never completes a Work Pack. The unsent hours are on the PM's screen (BPA-66). */
    @Test
    void BPA67_sentBackWeekLeavesWorkPackInProgress() {
        approvedWeek("100", "40");
        fixture.line(timesheetId(wp461, "E1021"), D16, "Cutting", new BigDecimal("6"), "Draft");
        long timesheets = timesheetInstanceAtBookHours(wp461);
        weekly = startWeekly(APPROVE_WEEK);
        JsonNode row = rowOf(wp461, json(flow.loggedInAs("project-manager").get("/api/v1/progress-periods/2026-11-19/workpacks").body()));
        assertThat(row.path("unsentLines")).hasSize(1);
        assertThat(row.path("unsentLines").get(0).path("hours").decimalValue()).isEqualByComparingTo("6");

        HttpResponse<String> sentBack = flow.loggedInAs("project-manager").post("/api/v1/tasks/" + awaitTask(weekly, APPROVE_WEEK) + "/complete", toJson(Map.of("variables",
                Map.of("weekDecision", "ChangesRequested", "weekChangeTarget", "Progress", "weekDecisionReason", "WP-000461: 6 h not sent"))));

        assertThat(sentBack.statusCode()).as(sentBack.body()).isEqualTo(204);
        awaitTask(weekly, COMPLETION);
        assertThat(workPackStatus(wp461)).isEqualTo("In Progress");
        assertThat(isActive(timesheets)).as("its time sheet instance is still running").isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM bpa.progress_period WHERE id = ?", String.class, period)).isEqualTo("CutOff");
    }

    // --- extras (not Gherkin scenarios) ---------------------------------------------------------------------------------------

    /** A retry of complete-workpacks publishes nothing again (the published_message guard) and does not complete the Work Pack twice. */
    @Test
    void aRerunOfCompleteWorkPacksPublishesOnce() {
        approvedWeek("100", "40");
        long timesheets = timesheetInstanceAtBookHours(wp461);
        weekly = startWeekly(COMPLETE_WORKPACKS);
        poll(() -> completed(timesheets, TIMESHEETS_CLOSED, 1), done -> done, "the time sheet instance ended");
        int audits = jdbc.queryForObject("SELECT count(*) FROM bpa.audit_log WHERE table_name = 'workpack' AND column_name = 'status' AND new_value = 'Completed'", Integer.class);

        completeWorker.handle(mock(JobClient.class, RETURNS_DEEP_STUBS), job(weekly));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.published_message WHERE sha256 = 'WorkPackCompleted' AND workpack_number = ?", Integer.class, wp461)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.audit_log WHERE table_name = 'workpack' AND column_name = 'status' AND new_value = 'Completed'", Integer.class))
                .as("no second Completed row").isEqualTo(audits);
        assertThat(audits).isEqualTo(1);
    }

    @Test
    void aRerunOfLockProgressPeriodChangesNothing() {
        approvedWeek("100", "40");
        weekly = startWeekly(COMPLETE_WORKPACKS);
        poll(() -> jdbc.queryForObject("SELECT status FROM bpa.progress_period WHERE id = ?", String.class, period), "Locked"::equals, "the period Locked");
        Timestamp lockedAt = jdbc.queryForObject("SELECT locked_at FROM bpa.progress_period WHERE id = ?", Timestamp.class, period);
        int audits = jdbc.queryForObject("SELECT count(*) FROM bpa.audit_log WHERE table_name = 'progress_period' AND record_id = ?", Integer.class, period);
        clock.set(TODAY.plusSeconds(3600));

        lockWorker.handle(mock(JobClient.class, RETURNS_DEEP_STUBS), job(weekly));

        assertThat(jdbc.queryForObject("SELECT locked_at FROM bpa.progress_period WHERE id = ?", Timestamp.class, period)).isEqualTo(lockedAt);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.audit_log WHERE table_name = 'progress_period' AND record_id = ?", Integer.class, period)).isEqualTo(audits);
    }

    /** The rows of a Locked week never change, whoever writes them: the rule result (BPA-61) and the reported % (BPA-59) were not guarded before BPA-67. */
    @Test
    void aLockedWeekRefusesTheRuleResultAndTheReportedPercent() {
        approvedWeek("100", "40");
        jdbc.update("UPDATE bpa.progress_period SET status = 'Locked', locked_at = now() WHERE id = ?", period);
        UUID report = jdbc.queryForObject("""
                INSERT INTO bpa.completion_report (progress_period_id, file_name, sha256, uploaded_by, received_at, created_by, updated_by)
                VALUES (?, 'fms-completion-wk47.xlsx', 'test', 'ppc-team', now(), 'test', 'test') RETURNING id""", UUID.class, period);
        jdbc.update("""
                INSERT INTO bpa.completion_report_row (completion_report_id, row_number, workpack_number, completion_pct_raw, actual_hours_raw, source_raw, source, outcome,
                                                       created_by, updated_by)
                VALUES (?, 2, 'WP-000457', '55', '150', 'FMS', 'FMS', 'Read', 'test', 'test')""", report);

        assertThatThrownBy(() -> progressChecks.record(new Period(period, W19),
                List.of(new ProgressCheckService.Result("WP-000457", new BigDecimal("40"), new BigDecimal("30"), "Blocked", "OVER_100")), "bpa-service"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(LOCKED);
        assertThatThrownBy(() -> completionProgress.decide(report, period, "bpa-service")).isInstanceOf(IllegalArgumentException.class).hasMessage(LOCKED);
        assertThat(jdbc.queryForList("SELECT rule_result FROM bpa.workpack_progress WHERE progress_period_id = ? AND rule_result IS NOT NULL", String.class, period)).isEmpty();
        assertThat(jdbc.queryForList("SELECT reported_pct FROM bpa.workpack_progress WHERE progress_period_id = ? AND reported_pct IS NOT NULL", BigDecimal.class, period)).isEmpty();
    }

    /** QA review: a stale instance on a Locked week fails the read step at once with no retries, as the other guarded record steps do (not three retries and an incident). */
    @Test
    void aLockedWeekFailsTheReadStepWithNoRetries() {
        approvedWeek("100", "40");
        jdbc.update("UPDATE bpa.progress_period SET status = 'Locked', locked_at = now() WHERE id = ?", period);
        UUID report = jdbc.queryForObject("""
                INSERT INTO bpa.completion_report (progress_period_id, file_name, sha256, uploaded_by, received_at, created_by, updated_by)
                VALUES (?, 'fms-completion-wk47.xlsx', 'test', 'ppc-team', now(), 'test', 'test') RETURNING id""", UUID.class, period);
        jdbc.update("""
                INSERT INTO bpa.completion_report_row (completion_report_id, row_number, workpack_number, completion_pct_raw, actual_hours_raw, source_raw, source, outcome,
                                                       created_by, updated_by)
                VALUES (?, 2, 'WP-000457', '55', '150', 'FMS', 'FMS', 'Read', 'test', 'test')""", report);
        ActivatedJob job = mock(ActivatedJob.class);
        when(job.getKey()).thenReturn(4_000_000_068L);
        when(job.getProcessInstanceKey()).thenReturn(9000000019L); // the period's instance key (approvedWeek)
        when(job.getVariablesAsMap()).thenReturn(Map.of("completionReportId", report.toString(), "completedBy", "ppc-team"));
        JobClient client = mock(JobClient.class, RETURNS_DEEP_STUBS);

        readWorker.handle(client, job);

        verify(client.newFailCommand(4_000_000_068L)).retries(0);
    }

    // --- helpers ----------------------------------------------------------------------------------------------------------------

    /** PPC's approved % for the week ending 19 Nov (by ppc-team) and the weekly period, opened for the instance started later. */
    private void approvedWeek(String pct461, String pct457) {
        jdbc.update("""
                INSERT INTO bpa.progress_period (week_ending, cut_off_at, status, process_instance_key, created_by, updated_by)
                VALUES (?, ?, 'CutOff', 9000000019, 'test', 'test')""", java.sql.Date.valueOf(W19), Timestamp.from(CUT_OFF));
        period = jdbc.queryForObject("SELECT id FROM bpa.progress_period WHERE week_ending = ?", UUID.class, java.sql.Date.valueOf(W19));
        approved(period, "WP-000457", pct457);
        approved(period, wp461, pct461);
    }

    private void approved(UUID periodId, String workpack, String pct) {
        jdbc.update("""
                INSERT INTO bpa.workpack_progress (workpack_id, progress_period_id, approved_pct, approved_by, approved_at, created_by, updated_by)
                SELECT id, ?, ?::numeric, 'ppc-team', now(), 'test', 'test' FROM bpa.workpack WHERE workpack_number = ?""", periodId, pct, workpack);
    }

    /** The weekly instance for the period, started at {@code elementId} (its period found from the variable, then linked as the real flow does). */
    private long startWeekly(String elementId) {
        long started = startAt(elementId, Map.of("progressPeriodId", period.toString(), "weekEnding", W19.toString()));
        jdbc.update("UPDATE bpa.progress_period SET process_instance_key = ? WHERE id = ?", started, period);
        return started;
    }

    private long startAt(String elementId, Map<String, Object> variables) {
        Reply started = IntegrationApp.admin.post("/v2/process-instances", Map.of(
                "processDefinitionId", WEEKLY, "variables", variables, "startInstructions", List.of(Map.of("elementId", elementId))));
        assertThat(started.status()).as("start at " + elementId + ": " + started.body()).isEqualTo(200);
        return Long.parseLong(started.body().path("processInstanceKey").asText());
    }

    private long timesheetInstanceAtBookHours(String workpackNumber) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("workpackId", workpackId(workpackNumber).toString());
        variables.put("workpackNumber", workpackNumber);
        Reply started = IntegrationApp.admin.post("/v2/process-instances", Map.of(
                "processDefinitionId", TIMESHEET, "variables", variables, "startInstructions", List.of(Map.of("elementId", BOOK_HOURS))));
        assertThat(started.status()).as("start at " + BOOK_HOURS + ": " + started.body()).isEqualTo(200);
        long instance = Long.parseLong(started.body().path("processInstanceKey").asText());
        awaitTask(instance, BOOK_HOURS);
        return instance;
    }

    /** The Work Pack's time sheet instance with E1021's 8 hours sent, validated and approved by production: it waits at UserTask_ApproveHoursPpc. */
    private long timesheetInstanceAtPpcApproval(String workpackNumber) {
        fixture.line(timesheetId(workpackNumber, "E1021"), D16, "Cutting", new BigDecimal("8"), "Draft");
        long instance = timesheetInstanceAtBookHours(workpackNumber);
        HttpResponse<String> sent = flow.loggedInAs("supervisor").post("/api/v1/tasks/" + awaitTask(instance, BOOK_HOURS) + "/complete", toJson(Map.of("variables", Map.of())));
        assertThat(sent.statusCode()).as("send: " + sent.body()).isEqualTo(204);
        assertThat(decide("timekeeper", awaitTask(instance, VALIDATE), "Validated").statusCode()).isEqualTo(204);
        assertThat(decide("production-supervisor", awaitTask(instance, APPROVE_PRODUCTION), "Approved").statusCode()).isEqualTo(204);
        awaitTask(instance, APPROVE_PPC);
        return instance;
    }

    private HttpResponse<String> decide(String user, String task, String decision) {
        return flow.loggedInAs(user).post("/api/v1/tasks/" + task + "/complete", toJson(Map.of("variables", Map.of("hoursDecision", decision))));
    }

    private String awaitTask(long instance, String elementId) {
        return poll(() -> openTasks(instance, elementId).stream().findFirst(), found -> found.isPresent(), elementId + " of " + instance).orElseThrow()
                .path("userTaskKey").asText();
    }

    private boolean isActive(long instance) {
        return !activeElements(instance).isEmpty() && !completed(instance, TIMESHEETS_CLOSED, 1);
    }

    private UUID workpackId(String number) {
        return jdbc.queryForObject("SELECT id FROM bpa.workpack WHERE workpack_number = ?", UUID.class, number);
    }

    private UUID timesheetId(String workpackNumber, String badge) {
        return jdbc.queryForObject("""
                SELECT t.id FROM bpa.digital_timesheet t JOIN bpa.worker w ON w.id = t.worker_id JOIN bpa.workpack p ON p.id = t.workpack_id
                WHERE p.workpack_number = ? AND w.employee_ref = ?""", UUID.class, workpackNumber, badge);
    }

    private String workPackStatus(String number) {
        return jdbc.queryForObject("SELECT status FROM bpa.workpack WHERE workpack_number = ?", String.class, number);
    }

    private BigDecimal approvedPct(String workpack) {
        return jdbc.queryForObject("""
                SELECT p.approved_pct FROM bpa.workpack_progress p JOIN bpa.workpack w ON w.id = p.workpack_id WHERE w.workpack_number = ? AND p.progress_period_id = ?""",
                BigDecimal.class, workpack, period);
    }

    private static JsonNode rowOf(String workpack, JsonNode week) {
        for (JsonNode row : week.path("workpacks")) {
            if (workpack.equals(row.path("workpackNumber").asText())) {
                return row;
            }
        }
        throw new AssertionError("no row for " + workpack + " in " + week);
    }

    /** A job as the engine would hand it over: the instance key finds the period (a mocked job for a retry). */
    private ActivatedJob job(long processInstanceKey) {
        ActivatedJob job = mock(ActivatedJob.class);
        when(job.getKey()).thenReturn(4_000_000_067L);
        when(job.getProcessInstanceKey()).thenReturn(processInstanceKey);
        when(job.getVariablesAsMap()).thenReturn(period == null ? Map.of() : Map.of("progressPeriodId", period.toString(), "weekEnding", W19.toString()));
        return job;
    }
}
