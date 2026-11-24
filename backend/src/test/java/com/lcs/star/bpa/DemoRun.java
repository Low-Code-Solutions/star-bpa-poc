package com.lcs.star.bpa;

import static com.lcs.star.bpa.AssignmentFlow.BOOK_HOURS;
import static com.lcs.star.bpa.AssignmentFlow.CATCH;
import static com.lcs.star.bpa.AssignmentFlow.CONFIRM;
import static com.lcs.star.bpa.AssignmentFlow.CREATE;
import static com.lcs.star.bpa.AssignmentFlow.SCHEDULE;
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
import com.lcs.star.bpa.AssignmentFlow.Upload;
import com.lcs.star.bpa.AssignmentFlow.Work;
import com.lcs.star.bpa.CamundaRest.Reply;
import com.lcs.star.bpa.api.TestBrowser;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * BPA-68 (E2-18): the steps of the demo run, one method per step of the Gherkin, each as the persona who takes it and through the task API the
 * portal uses (ADR 0014). One Work Pack on A-2301 goes from creation (Process_PPC_WorkPackAssignment) through its hours
 * (Process_PPC_TimesheetToProgress) to a locked week (Process_PPC_WeeklyProgressPeriod). The clock is the caller's; the cut-off is fired directly by starting the
 * weekly process at its first element. The demo data that Jira does not give is ASSUMPTION (plan BPA-68, Design section 2). Test sources only: no production code.
 */
final class DemoRun {

    static final String WEEKLY = "Process_PPC_WeeklyProgressPeriod";
    static final String TIMESHEET = "Process_PPC_TimesheetToProgress";
    static final String ACTIVITY = "A-2301";
    static final String CBS = "CBS-139-PIP-01";
    static final LocalDate W19 = LocalDate.of(2026, 11, 19);
    /** 16 Nov 2026 09:00 in Riyadh. */
    static final Instant MONDAY = Instant.parse("2026-11-16T06:00:00Z");
    /** 17 Nov 2026 09:00 in Riyadh: the day the 17 h line is dated (plan Q5, ASSUMPTION). */
    static final Instant TUESDAY = Instant.parse("2026-11-17T06:00:00Z");
    /** Thursday 19 Nov 2026 23:59 in Riyadh. */
    static final Instant CUT_OFF = Instant.parse("2026-11-19T20:59:00Z");

    static final String OPEN_PERIOD = "ServiceTask_OpenProgressPeriod";
    static final String SEND_FMS = "UserTask_SendHoursToFms";
    static final String UPLOAD = "UserTask_UploadCompletionReport";
    static final String APPROVE_COMPLETION = "UserTask_ApproveCompletion";
    static final String REVIEW = "UserTask_ReviewActualManhours";
    static final String APPROVE_WEEK = "UserTask_ApproveWeeklyProgress";
    static final String VALIDATE = "UserTask_ValidateHours";
    static final String APPROVE_PRODUCTION = "UserTask_ApproveHoursProduction";
    static final String APPROVE_PPC = "UserTask_ApproveHoursPpc";
    static final String LOCKED_END = "EndEvent_PeriodLocked";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final int port;
    private final JdbcTemplate jdbc;
    private final AssignmentFileApiTest.TestClock clock;
    final AssignmentFlow flow;

    /** What a run knows about its Work Pack. */
    record Run(Work work, long timesheetInstance, Map<String, String> timesheets) {
        String number() {
            return work.number();
        }
    }

    DemoRun(int port, JdbcTemplate jdbc, AssignmentFileApiTest.TestClock clock) {
        this.port = port;
        this.jdbc = jdbc;
        this.clock = clock;
        this.flow = new AssignmentFlow(port, jdbc);
    }

    // --- before each test ------------------------------------------------------------------------------------------------------

    /** No instance left waiting by another class may take this run's tasks or messages: all classes share one Camunda (plan Risks). */
    static void cancelWaiting() {
        for (String element : new String[] {CREATE, CONFIRM, "UserTask_SignOffRelease", SCHEDULE, CATCH, BOOK_HOURS, VALIDATE, APPROVE_PRODUCTION, APPROVE_PPC, OPEN_PERIOD,
                "ServiceTask_GenerateFmsHoursFile", SEND_FMS, "ServiceTask_RecordFmsHandOff", UPLOAD, "ServiceTask_ReadCompletionReport", APPROVE_COMPLETION,
                "ServiceTask_RecordCompletionApproval", "ServiceTask_PrepareProgressCheck", "BusinessRuleTask_CheckProgressRule", "ServiceTask_RecordProgressResult",
                "UserTask_ApproveRetraction", "ServiceTask_RecordRetractionDecision", "ServiceTask_CalculateEarnedManhours", "ServiceTask_PrepareVarianceCheck",
                "BusinessRuleTask_CheckManhourVariance", "BusinessRuleTask_CheckCostVariance", "ServiceTask_SendVarianceAlerts", REVIEW, "ServiceTask_RecordCbsReview",
                APPROVE_WEEK, "ServiceTask_RecordWeekDecision", "ServiceTask_CompleteWorkPacks", "ServiceTask_LockProgressPeriod"}) {
            CamundaSteps.cancelInstancesWaitingAt(element);
        }
    }

    /**
     * A clean database for the run: every row the POC masters is removed and the Work Pack numbers start at WP-000001 again, so the demo Work Pack is the only
     * one (03 SOR matrix, POC-mastered data); the seed (A-2301, E1021, E1099, the personas) stays.
     */
    void reset() {
        cancelWaiting();
        jdbc.execute("""
                TRUNCATE bpa.workpack, bpa.assignment_file, bpa.published_message, bpa.progress_period, bpa.hours_month_lock, bpa.cbs_manhour_snapshot, bpa.cbs_review,
                         bpa.variance_alert, bpa.activity_progress, bpa.notification, bpa.audit_log RESTART IDENTITY CASCADE""");
        jdbc.execute("ALTER SEQUENCE bpa.workpack_number_seq RESTART WITH 1");
        new RevisionFlow(port, jdbc).seedAppUsers();
        clock.set(MONDAY);
    }

    // --- Epic 1: Process_PPC_WorkPackAssignment ----------------------------------------------------------------------------------

    /**
     * The Time &amp; Method Analyst creates the Work Pack for A-2301 with Cutting 300 h and Welding 200 h (Jira), confirms it, the Project Engineer signs it off, the
     * Production Planner schedules it (23 Nov to 18 Dec: today 16 Nov plus the 7-day lead time, ASSUMPTION), it is printed, Production Team uploads the assignment file
     * with E1021 and E1099, and the timesheet process waits at UserTask_BookHours.
     */
    Run epic1() {
        TestBrowser analyst = flow.loggedInAs("time-method-analyst");
        HttpResponse<String> started = AssignmentFlow.startWorkPack(analyst, jdbc);
        assertThat(started.statusCode()).as("POST /api/v1/workpacks: " + started.body()).isEqualTo(201);
        long instance = Long.parseLong(json(started.body()).path("processInstanceKey").asText());
        String create = awaitOpenTask(instance, CREATE).path("userTaskKey").asText();
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("activityRef", ACTIVITY);
        variables.put("drawingRef", "DWG-118");
        variables.put("phases", List.of(Map.of("phase", "Cutting", "manhours", 300), Map.of("phase", "Welding", "manhours", 200)));
        variables.put("materials", List.of(Map.of("materialRef", "MAT-PIPE-06", "phase", "Cutting")));
        variables.put("departmentRef", "Fabrication");
        variables.put("completionDate", "2026-12-31");
        assertThat(analyst.post("/api/v1/tasks/" + create + "/complete", toJson(Map.of("variables", variables))).statusCode()).as("complete Create").isEqualTo(204);

        String confirm = awaitOpenTask(instance, CONFIRM).path("userTaskKey").asText();
        HttpResponse<String> open = analyst.get("/api/v1/tasks/" + confirm);
        Map<String, Object> shown = JSON.convertValue(json(open.body()).path("variables"), new TypeReference<Map<String, Object>>() {
        });
        assertThat(analyst.post("/api/v1/tasks/" + confirm + "/complete", toJson(Map.of("variables", shown))).statusCode()).as("complete Confirm").isEqualTo(204);

        HttpResponse<String> signed = completeSignOff(flow.loggedInAs("project-engineer"), awaitSignOffTask(instance), "Approved", null);
        assertThat(signed.statusCode()).as("sign-off: " + signed.body()).isEqualTo(204);

        String schedule = awaitOpenTask(instance, SCHEDULE).path("userTaskKey").asText();
        HttpResponse<String> scheduled = flow.loggedInAs("production-planner").post("/api/v1/tasks/" + schedule + "/complete",
                toJson(Map.of("variables", Map.of("plannedStartDate", "2026-11-23", "plannedEndDate", "2026-12-18"))));
        assertThat(scheduled.statusCode()).as("complete Schedule: " + scheduled.body()).isEqualTo(204);

        UUID id = jdbc.queryForObject("SELECT id FROM bpa.workpack WHERE process_instance_key = ?", UUID.class, instance);
        String number = jdbc.queryForObject("SELECT workpack_number FROM bpa.workpack WHERE id = ?", String.class, id);
        Work work = new Work(instance, id, number, confirm, schedule);
        poll(() -> AssignmentFlow.activeIds(work), ids -> ids.contains(CATCH), "the instance of " + number + " waiting for the assignment file");
        HttpResponse<byte[]> print = flow.loggedInAs("time-method-analyst").getBytes("/api/v1/workpacks/" + number + "/print");
        assertThat(print.statusCode()).as("the printed Work Pack").isEqualTo(200);
        assertThat(flow.state(work)).isEqualTo("AwaitingFile");

        Upload upload = flow.upload("production-team", "assign-demo-" + number + ".xlsx",
                AssignmentFileBuilder.assignment().row(number, "E1021", 40).row(number, "E1099", 60).build());
        assertThat(flow.settled(upload)).as("every row of the assignment file").allMatch("Assigned"::equals);
        flow.awaitEnd(work);
        assertThat(flow.state(work)).isEqualTo("Assigned");

        Map<String, String> sheets = timesheets(number);
        long timesheetInstance = Long.parseLong(poll(() -> timesheetInstances(number), found -> !found.isEmpty(), "the timesheet process of " + number).get(0));
        awaitOpenTask(timesheetInstance, BOOK_HOURS);
        return new Run(work, timesheetInstance, sheets);
    }

    /** The two time sheets of the Work Pack as the Supervisor finds them: badge to time sheet id. */
    private Map<String, String> timesheets(String number) {
        HttpResponse<String> found = poll(() -> flow.loggedInAs("supervisor").get("/api/v1/digital-timesheets?workpackNumber=" + number),
                r -> r.statusCode() == 200 && elements(json(r.body())).size() == 2, "two time sheets of " + number);
        Map<String, String> byBadge = new LinkedHashMap<>();
        for (JsonNode sheet : elements(json(found.body()))) {
            byBadge.put(sheet.path("employeeRef").asText(), sheet.path("id").asText());
        }
        return byBadge;
    }

    // --- Epic 2: hours -------------------------------------------------------------------------------------------------------------

    /** The Supervisor books a line on the Worker's time sheet; the reply is returned for the caller to check (201, or the refusal). */
    HttpResponse<String> book(Run run, String badge, LocalDate date, String phase, String hours) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workDate", date.toString());
        body.put("phase", phase);
        body.put("hours", new BigDecimal(hours));
        return flow.loggedInAs("supervisor").post("/api/v1/digital-timesheets/" + run.timesheets().get(badge) + "/lines", toJson(body));
    }

    /** E1021 and E1099 on 16 Nov, Cutting, 8 h and 7.5 h (Jira). */
    void bookTheDay(Run run) {
        assertThat(book(run, "E1021", LocalDate.of(2026, 11, 16), "Cutting", "8").statusCode()).as("E1021 line").isEqualTo(201);
        assertThat(book(run, "E1099", LocalDate.of(2026, 11, 16), "Cutting", "7.5").statusCode()).as("E1099 line").isEqualTo(201);
    }

    /** The Supervisor completes UserTask_BookHours: "Send to Timekeeper". */
    void send(Run run) {
        String task = awaitOpenTask(run.timesheetInstance(), BOOK_HOURS).path("userTaskKey").asText();
        HttpResponse<String> sent = flow.loggedInAs("supervisor").post("/api/v1/tasks/" + task + "/complete", toJson(Map.of("variables", Map.of())));
        assertThat(sent.statusCode()).as("send: " + sent.body()).isEqualTo(204);
    }

    HttpResponse<String> decideHours(Run run, String element, String persona, String decision, String reason) {
        String task = awaitOpenTask(run.timesheetInstance(), element).path("userTaskKey").asText();
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("hoursDecision", decision);
        if (reason != null) {
            variables.put("hoursReason", reason);
        }
        return flow.loggedInAs(persona).post("/api/v1/tasks/" + task + "/complete", toJson(Map.of("variables", variables)));
    }

    /** Validated, then approved by the Production Supervisor and by PPC; the instance is back at Book hours and the lines are Approved. */
    void approveHours(Run run) {
        assertThat(decideHours(run, VALIDATE, "timekeeper", "Validated", null).statusCode()).as("Validated").isEqualTo(204);
        assertThat(decideHours(run, APPROVE_PRODUCTION, "production-supervisor", "Approved", null).statusCode()).as("Approved by Production").isEqualTo(204);
        assertThat(decideHours(run, APPROVE_PPC, "ppc-team", "Approved", null).statusCode()).as("Approved by PPC").isEqualTo(204);
        poll(() -> jdbc.queryForObject("SELECT count(*) FROM bpa.digital_timesheet_line WHERE status = 'Approved'", Integer.class), n -> n == 2, "both lines Approved");
        awaitOpenTask(run.timesheetInstance(), BOOK_HOURS);
    }

    /** The Supervisor corrects E1099's 16 Nov line (ASSUMPTION: 7.5 h to 7 h) after the Timekeeper returned the hours. */
    void correctE1099(Run run) {
        UUID line = jdbc.queryForObject("""
                SELECT l.id FROM bpa.digital_timesheet_line l JOIN bpa.digital_timesheet t ON t.id = l.digital_timesheet_id WHERE t.id = ?::uuid AND l.work_date = DATE '2026-11-16'""",
                UUID.class, run.timesheets().get("E1099"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("hours", new BigDecimal("7"));
        body.put("remarks", "Corrected after Timekeeper check");
        HttpResponse<String> changed = flow.loggedInAs("supervisor").put("/api/v1/digital-timesheet-lines/" + line, toJson(body));
        assertThat(changed.statusCode()).as("correct E1099: " + changed.body()).isEqualTo(200);
    }

    int lines(String status) {
        return jdbc.queryForObject("SELECT count(*) FROM bpa.digital_timesheet_line WHERE status = ?", Integer.class, status);
    }

    // --- Epic 2: the week --------------------------------------------------------------------------------------------------------

    /** Thursday 19 Nov 23:59 Riyadh: the clock moves and the cut-off is fired directly (the timer itself is not waited for); returns the weekly instance. */
    long cutOff() {
        clock.set(CUT_OFF);
        Reply started = IntegrationApp.admin.post("/v2/process-instances", Map.of("processDefinitionId", WEEKLY, "startInstructions", List.of(Map.of("elementId", OPEN_PERIOD))));
        assertThat(started.status()).as("cut-off: " + started.body()).isEqualTo(200);
        long instance = Long.parseLong(started.body().path("processInstanceKey").asText());
        awaitOpenTask(instance, SEND_FMS);
        return instance;
    }

    /** PPC sends every file of the week to FMS; returns the rows of the demo Work Pack in them (activity A-2301), the others ignored (Jira decision (a)). */
    List<List<String>> sendFmsFile(long weekly) {
        List<UUID> exports = jdbc.queryForList("""
                SELECT e.id FROM bpa.fms_hours_export e JOIN bpa.progress_period p ON p.id = e.progress_period_id WHERE p.week_ending = ? ORDER BY e.period_month""",
                UUID.class, java.sql.Date.valueOf(W19));
        assertThat(exports).as("the FMS file of the week").isNotEmpty();
        List<List<String>> demoRows = new ArrayList<>();
        for (UUID export : exports) {
            for (List<String> row : download(export)) {
                if (ACTIVITY.equals(row.get(5))) {
                    demoRows.add(row);
                }
            }
        }
        List<Map<String, Object>> handOffs = new ArrayList<>();
        for (UUID export : exports) {
            handOffs.add(Map.of("fmsHoursExportId", export.toString(), "outcome", "Sent to FMS"));
        }
        String task = awaitOpenTask(weekly, SEND_FMS).path("userTaskKey").asText();
        HttpResponse<String> sent = flow.loggedInAs("ppc-team").post("/api/v1/tasks/" + task + "/complete", toJson(Map.of("variables", Map.of("fmsHandOffs", handOffs))));
        assertThat(sent.statusCode()).as("send to FMS: " + sent.body()).isEqualTo(204);
        return demoRows;
    }

    /** The file as PPC downloads it: the formatted value of the first ten columns of every row after the header. */
    private List<List<String>> download(UUID export) {
        HttpResponse<byte[]> response = flow.loggedInAs("ppc-team").getBytes("/api/v1/fms-hours-exports/" + export + "/file");
        assertThat(response.statusCode()).as("download the FMS file").isEqualTo(200);
        DataFormatter formatter = new DataFormatter();
        List<List<String>> rows = new ArrayList<>();
        try (Workbook book = new XSSFWorkbook(new ByteArrayInputStream(response.body()))) {
            Sheet sheet = book.getSheet("FMS Hours");
            assertThat(sheet).as("sheet FMS Hours").isNotNull();
            for (Row row : sheet) {
                if (row.getRowNum() == 0) {
                    continue;
                }
                List<String> values = new ArrayList<>();
                for (int c = 0; c < 10; c++) {
                    Cell cell = row.getCell(c);
                    values.add(cell == null ? "" : formatter.formatCellValue(cell));
                }
                rows.add(values);
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return rows;
    }

    /** PPC uploads the completion report with the Work Pack at 40 % (Jira; the 15.5 actual hours are ASSUMPTION) and completes the task with it. */
    void uploadReport(long weekly, Run run) {
        String task = awaitOpenTask(weekly, UPLOAD).path("userTaskKey").asText();
        byte[] file = CompletionReportBuilder.report().row(run.number(), 40, new BigDecimal("15.5"), "FMS").xlsx();
        HttpResponse<String> uploaded = flow.loggedInAs("ppc-team").postMultipart("/api/v1/completion-reports", Map.of("taskKey", task), "completion-" + run.number() + ".xlsx", file);
        assertThat(uploaded.statusCode()).as("upload report: " + uploaded.body()).isEqualTo(201);
        String report = json(uploaded.body()).path("id").asText();
        HttpResponse<String> done = flow.loggedInAs("ppc-team").post("/api/v1/tasks/" + task + "/complete", toJson(Map.of("variables", Map.of("completionReportId", report))));
        assertThat(done.statusCode()).as("complete the upload task: " + done.body()).isEqualTo(204);
    }

    /** PPC approves the Work Pack at {@code pct} on the open Approve completion task; the reply is returned (204, or the refusal, the task staying open). */
    HttpResponse<String> approveCompletion(long weekly, Run run, String pct) {
        String task = awaitOpenTask(weekly, APPROVE_COMPLETION).path("userTaskKey").asText();
        Map<String, Object> approval = new LinkedHashMap<>();
        approval.put("workpackNumber", run.number());
        approval.put("approvedPct", new BigDecimal(pct));
        return flow.loggedInAs("ppc-team").post("/api/v1/tasks/" + task + "/complete",
                toJson(Map.of("variables", Map.of("completionApprovals", List.of(approval)))));
    }

    /** Cost Controller completes the review of the week (the remark is ASSUMPTION), the Project Manager approves the week; returns when the instance ends at EndEvent_PeriodLocked. */
    void closeTheWeek(long weekly) {
        String review = awaitOpenTask(weekly, REVIEW).path("userTaskKey").asText();
        List<Map<String, Object>> reviews = List.of(Map.of("cbsCodeRef", CBS, "remark", "Reviewed for the demo"));
        HttpResponse<String> reviewed = flow.loggedInAs("cost-controller").post("/api/v1/tasks/" + review + "/complete", toJson(Map.of("variables", Map.of("cbsReviews", reviews))));
        assertThat(reviewed.statusCode()).as("cost review: " + reviewed.body()).isEqualTo(204);
        String week = awaitOpenTask(weekly, APPROVE_WEEK).path("userTaskKey").asText();
        HttpResponse<String> approved = flow.loggedInAs("project-manager").post("/api/v1/tasks/" + week + "/complete",
                toJson(Map.of("variables", Map.of("weekDecision", "Approved"))));
        assertThat(approved.statusCode()).as("approve the week: " + approved.body()).isEqualTo(204);
        poll(() -> completed(weekly, LOCKED_END, 1), done -> done, "the weekly instance at " + LOCKED_END);
    }

    // --- what the end state is -----------------------------------------------------------------------------------------------------

    /** The run's end state, as the Gherkin Then lines read it. */
    void assertEndState(Run run, List<List<String>> fmsRows, String e1099Hours) {
        assertThat(fmsRows).as("FMS file rows of the demo Work Pack (others ignored)").hasSize(2);
        // QA review: the rows themselves, not only their count: Badge No. (column 3), Date (2) and Hrs (10) of E1021 and of E1099 (7.5 h, or 7 h after the correction)
        assertThat(fmsRows).extracting(r -> r.get(2) + " " + r.get(1) + " " + new BigDecimal(r.get(9).trim()).stripTrailingZeros().toPlainString())
                .containsExactlyInAnyOrder("E1021 16/11/2026 8", "E1099 16/11/2026 " + e1099Hours);
        assertThat(jdbc.queryForObject("""
                SELECT a.earned_manhours FROM bpa.activity_progress a JOIN bpa.progress_period p ON p.id = a.progress_period_id WHERE a.activity_ref = ? AND p.week_ending = ?""",
                BigDecimal.class, ACTIVITY, java.sql.Date.valueOf(W19))).as("A-2301 Earned man-hours").isEqualByComparingTo("480.00");
        HttpResponse<String> earned = flow.loggedInAs("ppc-team").get("/api/v1/progress-periods/2026-11-19/earned-manhours");
        assertThat(earned.statusCode()).isEqualTo(200);
        BigDecimal shown = null;
        for (JsonNode activity : json(earned.body()).path("activities")) {
            if (ACTIVITY.equals(activity.path("activityRef").asText())) {
                shown = activity.path("earnedManhours").decimalValue();
            }
        }
        assertThat(shown).as("A-2301 Earned man-hours as the screen's API shows them").isEqualByComparingTo("480.00");
        assertThat(jdbc.queryForObject("SELECT status FROM bpa.progress_period WHERE week_ending = ?", String.class, java.sql.Date.valueOf(W19))).as("the period").isEqualTo("Locked");
        assertThat(jdbc.queryForObject("SELECT status FROM bpa.workpack WHERE workpack_number = ?", String.class, run.number())).as("the Work Pack").isEqualTo("In Progress");
        Reply timesheet = IntegrationApp.admin.get("/v2/process-instances/" + run.timesheetInstance());
        assertThat(timesheet.body().path("state").asText()).as("the timesheet process").isEqualTo("ACTIVE");
        assertThat(awaitOpenTask(run.timesheetInstance(), BOOK_HOURS).path("state").asText()).as("waiting at " + BOOK_HOURS).isEqualTo("CREATED");
    }

    // --- reading Camunda -----------------------------------------------------------------------------------------------------------

    /** The running Process_PPC_TimesheetToProgress instances of this Work Pack number (found by the variable the message carries). */
    private static List<String> timesheetInstances(String workpackNumber) {
        List<String> found = new ArrayList<>();
        for (JsonNode variable : elements(IntegrationApp.admin.post("/v2/variables/search",
                Map.of("filter", Map.of("name", "workpackNumber"), "page", Map.of("limit", 1000))).body().path("items"))) {
            String instance = variable.path("processInstanceKey").asText();
            if (variable.path("value").asText().contains(workpackNumber) && !found.contains(instance)) {
                JsonNode instanceBody = IntegrationApp.admin.get("/v2/process-instances/" + instance).body();
                if (TIMESHEET.equals(instanceBody.path("processDefinitionId").asText()) && "ACTIVE".equals(instanceBody.path("state").asText())) {
                    found.add(instance);
                }
            }
        }
        return found;
    }
}
