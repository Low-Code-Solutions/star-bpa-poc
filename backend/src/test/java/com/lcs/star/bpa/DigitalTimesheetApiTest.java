package com.lcs.star.bpa;

import static com.lcs.star.bpa.AssignmentFlow.CATCH;
import static com.lcs.star.bpa.CamundaSteps.completed;
import static com.lcs.star.bpa.CamundaSteps.elements;
import static com.lcs.star.bpa.CamundaSteps.json;
import static com.lcs.star.bpa.CamundaSteps.poll;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.lcs.star.bpa.AssignmentFlow.Upload;
import com.lcs.star.bpa.AssignmentFlow.Work;
import com.lcs.star.bpa.api.TestBrowser;
import com.lcs.star.bpa.worker.CreateDigitalTimesheetsWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
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
 * BPA-15: the Digital Time sheets created by {@code create-digital-timesheets} (ServiceTask_CreateDigitalTimesheets) and read by
 * {@code GET /api/v1/digital-timesheets?workpackNumber=}. One test per Gherkin scenario, named as in the Jira DoD. Today is 30 Oct 2026
 * (Asia/Riyadh). "The Lines are saved" is the real path: a Work Pack at AwaitingFile (AssignmentFlow), an assignment file from the
 * AssignmentFileBuilder (never templates/demo; S18), BPA-22 assigning the Workers, BPA-14 creating the Lines, and then this step, which ends
 * Process_PPC_WorkPackAssignment at EndEvent_WorkPackAssigned. The Work Pack's activity A-2201 has CBS code CBS-139-PIP-01 and Project PRJ-139
 * / WBS 11031; A-2299 has no CBS code; E1021 and E1099 have cost centre STRP01 and E1150 has none (seed). The reader, the group supervisor,
 * is an ASSUMPTION in the story (09 S6); the empty list for a Work Pack without time sheets is ours.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(AssignmentFileApiTest.TestClockConfig.class)
class DigitalTimesheetApiTest {

    static final String CREATE_TIMESHEETS = "ServiceTask_CreateDigitalTimesheets";
    static final String TIMESHEETS = "/api/v1/digital-timesheets?workpackNumber=";

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
    CreateDigitalTimesheetsWorker worker;

    private AssignmentFlow flow;

    /** All test classes share one Camunda and Work Pack numbers repeat, so an instance another class left at the catch event could take our message. */
    @BeforeAll
    static void noInstanceLeftWaitingByAnotherClass() {
        IntegrationApp.background();
        CamundaSteps.cancelInstancesWaitingAt(CATCH);
    }

    @BeforeEach
    void today() {
        clock.set(AssignmentFileApiTest.TODAY);
        flow = new AssignmentFlow(port, jdbc);
    }

    // --- the scenarios ----------------------------------------------------------------------------------------------------

    @Test
    void BPA15_timesheetsAreCreatedForAssignedWorkers() {
        Work wp = ended(flow.awaiting(), Map.of("E1021", 40, "E1099", 60));

        List<JsonNode> sheets = sheetsOf(wp, 2);

        assertThat(sheets).extracting(s -> String.join("/", s.path("employeeRef").asText(), s.path("projectRef").asText(), s.path("cbsCodeRef").asText(),
                s.path("costCentreRef").asText(), s.path("wbsRef").asText(), s.path("activityRef").asText(), s.path("submissionStatus").asText()))
                .containsExactly("E1021/PRJ-139/CBS-139-PIP-01/STRP01/11031/A-2201/Draft", "E1099/PRJ-139/CBS-139-PIP-01/STRP01/11031/A-2201/Draft");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.digital_timesheet_line", Integer.class)).as("no daily lines (Epic 2)").isZero();
        assertThat(completed(wp.instance(), AssignmentFlow.END, 1)).as("the assignment process ends at EndEvent_WorkPackAssigned").isTrue();
    }

    @Test
    void BPA15_rejectedRowsGetNoTimesheet() {
        Work wp = flow.awaiting();
        Upload upload = flow.upload("production-team", "assign-sheets-rejected.xlsx",
                AssignmentFileBuilder.assignment().row(wp.number(), "E1021", 40).row(wp.number(), "E9999", 20).build());
        assertThat(settled(upload)).containsExactly("Assigned", "Worker not in worker master data");
        flow.awaitEnd(wp);

        assertThat(sheetsOf(wp, 1)).extracting(s -> s.path("employeeRef").asText()).containsExactly("E1021");
        assertThat(sheetCount(wp)).as("one time sheet on the Work Pack, none for E9999 (it has no assignment)").isEqualTo(1);
    }

    @Test
    void BPA15_workerOnTwoWorkPacksHasTwoTimesheets() {
        Work a = flow.awaiting();
        Work b = flow.awaiting();
        Upload upload = flow.upload("production-team", "assign-sheets-two.xlsx",
                AssignmentFileBuilder.assignment().row(a.number(), "E1021", 40).row(b.number(), "E1021", 20).build());
        assertThat(settled(upload)).containsExactly("Assigned", "Assigned");
        flow.awaitEnd(a);
        flow.awaitEnd(b);

        assertThat(sheetsOf(a, 1)).extracting(s -> s.path("employeeRef").asText() + "/" + s.path("workpackNumber").asText()).containsExactly("E1021/" + a.number());
        assertThat(sheetsOf(b, 1)).extracting(s -> s.path("employeeRef").asText() + "/" + s.path("workpackNumber").asText()).containsExactly("E1021/" + b.number());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.digital_timesheet t JOIN bpa.worker w ON w.id = t.worker_id WHERE w.employee_ref = 'E1021'",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void BPA15_missingCbsOrCostCentreStillCreatesTimesheet() {
        Work wp = ended(flow.awaiting("A-2299"), Map.of("E1150", 5));

        List<JsonNode> sheets = sheetsOf(wp, 1);

        JsonNode sheet = sheets.get(0);
        assertThat(sheet.path("employeeRef").asText()).isEqualTo("E1150");
        assertThat(sheet.path("projectRef").asText()).as("Project ID filled").isEqualTo("PRJ-139");
        assertThat(sheet.path("wbsRef").asText()).as("WBS filled").isEqualTo("11031");
        assertThat(sheet.path("activityRef").asText()).isEqualTo("A-2299");
        assertThat(sheet.has("cbsCodeRef")).as("CBS Code is blank (absent)").isFalse();
        assertThat(sheet.has("costCentreRef")).as("Cost Centre is blank (absent)").isFalse();
        Map<String, Object> row = jdbc.queryForMap("SELECT cbs_code_ref, cost_centre_ref FROM bpa.digital_timesheet WHERE workpack_id = ?", wp.id());
        assertThat(row.get("cbs_code_ref")).isNull();
        assertThat(row.get("cost_centre_ref")).isNull();
    }

    @Test
    void BPA15_rerunDoesNotDuplicateTimesheets() {
        Work wp = ended(flow.awaiting(), Map.of("E1021", 40, "E1099", 60));
        sheetsOf(wp, 2);
        int audit = sheetAuditRows(wp);
        JobClient client = Mockito.mock(JobClient.class, Mockito.RETURNS_DEEP_STUBS);

        worker.handle(client, job(81L, wp.instance()));
        worker.handle(client, job(82L, wp.instance()));

        Mockito.verify(client).newCompleteCommand(81L);
        Mockito.verify(client).newCompleteCommand(82L);
        assertThat(sheetCount(wp, "E1021")).as("exactly one time sheet for E1021 on the Work Pack").isEqualTo(1);
        assertThat(sheetCount(wp, "E1099")).as("and one for E1099").isEqualTo(1);
        assertThat(sheetAuditRows(wp)).as("the re-run adds no audit row").isEqualTo(audit);
        assertThat(flow.state(wp)).isEqualTo("Assigned");
    }

    @Test
    void BPA15_workPackIsMarkedAssigned() {
        Work wp = ended(flow.awaiting(), Map.of("E1021", 40));

        assertThat(flow.state(wp)).isEqualTo("Assigned");
        assertThat(jdbc.queryForList("""
                SELECT old_value || '->' || new_value || ' by ' || username FROM bpa.audit_log
                WHERE table_name = 'workpack' AND record_id = ? AND column_name = 'assignment_state' AND old_value IS NOT NULL ORDER BY changed_at, old_value
                """, String.class, wp.id())).as("D64: BPA-22's change and this step's").containsExactlyInAnyOrder("AwaitingFile->Assigning by production-team",
                "Assigning->Assigned by production-team");
    }

    @Test
    void BPA15_supervisorFindsTimesheets() {
        Work wp = ended(flow.awaiting(), Map.of("E1021", 40, "E1099", 60));

        HttpResponse<String> response = poll(() -> flow.loggedInAs("supervisor").get(TIMESHEETS + wp.number()),
                r -> r.statusCode() == 200 && elements(json(r.body())).size() == 2, "two time sheets of " + wp.number() + " for the supervisor");

        List<JsonNode> sheets = elements(json(response.body()));
        assertThat(sheets).extracting(s -> s.path("employeeRef").asText()).containsExactly("E1021", "E1099");
        for (JsonNode sheet : sheets) {
            assertThat(sheet.path("workpackNumber").asText()).isEqualTo(wp.number());
            assertThat(sheet.path("projectRef").asText()).isEqualTo("PRJ-139");
            assertThat(sheet.path("wbsRef").asText()).isEqualTo("11031");
            assertThat(sheet.path("activityRef").asText()).isEqualTo("A-2201");
            assertThat(sheet.path("cbsCodeRef").asText()).isEqualTo("CBS-139-PIP-01");
            assertThat(sheet.path("costCentreRef").asText()).isEqualTo("STRP01");
            assertThat(sheet.path("submissionStatus").asText()).isEqualTo("Draft");
        }
    }

    @Test
    void BPA15_otherRolesCannotReadTimesheets() {
        Work wp = ended(flow.awaiting(), Map.of("E1021", 40));
        sheetsOf(wp, 1);

        HttpResponse<String> response = flow.loggedInAs("production-team").get(TIMESHEETS + wp.number());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).as("no time sheet data is returned").doesNotContain("employeeRef").doesNotContain("PRJ-139");
    }

    @Test
    void BPA15_unknownWorkPackReturnsNotFound() {
        HttpResponse<String> response = flow.loggedInAs("supervisor").get(TIMESHEETS + "WP-999999");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json(response.body()).path("detail").asText()).isEqualTo("Work Pack not found");
    }

    // --- not scenarios ---------------------------------------------------------------------------------------------------------

    /** Only the supervisor reads (ASSUMPTION in the story); every other persona, HEAD, a trailing slash and anonymous callers follow the same rule (the BPA-10 lesson). */
    @Test
    void onlyTheSupervisorReadsAndHeadAnonymousAndTheOtherPersonasFollowTheRule() {
        Work wp = ended(flow.awaiting(), Map.of("E1021", 40));
        sheetsOf(wp, 1);

        for (String persona : List.of("ppc-team", "production-planner", "project-engineer", "time-method-analyst", "workpack-owner")) {
            assertThat(flow.loggedInAs(persona).get(TIMESHEETS + wp.number()).statusCode()).as(persona).isEqualTo(403);
        }
        assertThat(flow.loggedInAs("production-planner").head(TIMESHEETS + wp.number()).statusCode()).as("HEAD is not a way round").isEqualTo(403);
        assertThat(new TestBrowser(port).get(TIMESHEETS + wp.number()).statusCode()).isEqualTo(401);
        HttpResponse<String> slash = flow.loggedInAs("production-planner").get("/api/v1/digital-timesheets/?workpackNumber=" + wp.number());
        assertThat(slash.statusCode()).isNotEqualTo(200);
        assertThat(slash.body()).doesNotContain("employeeRef");
    }

    @Test
    void aMalformedOrMissingWorkpackNumberIs400() {
        TestBrowser supervisor = flow.loggedInAs("supervisor");

        HttpResponse<String> malformed = supervisor.get(TIMESHEETS + "WP-12");
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(json(malformed.body()).path("detail").asText()).isEqualTo("Workpack number must match WP-######");
        assertThat(supervisor.get("/api/v1/digital-timesheets").statusCode()).isEqualTo(400);
    }

    /** A Work Pack that has no time sheets yet answers 200 with an empty list (ASSUMPTION, as BPA-7 and BPA-14). */
    @Test
    void aWorkPackWithoutTimesheetsYetAnswersAnEmptyList() {
        Work wp = flow.awaiting();

        HttpResponse<String> response = flow.loggedInAs("supervisor").get(TIMESHEETS + wp.number());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(elements(json(response.body()))).isEmpty();
    }

    /** Every business column of a new time sheet is audited, by the uploader, with one instant from the clock; a blank column writes no row (D64, 6.1; D63). */
    @Test
    void aTimesheetIsAuditedByTheUploader() {
        Work wp = ended(flow.awaiting(), Map.of("E1021", 40));
        sheetsOf(wp, 1);

        List<String> audit = jdbc.queryForList("""
                SELECT a.column_name || '=' || coalesce(a.new_value, 'null') || ' (old ' || coalesce(a.old_value, 'null') || ') by ' || a.username
                FROM bpa.audit_log a JOIN bpa.digital_timesheet t ON t.id = a.record_id
                WHERE a.table_name = 'digital_timesheet' AND t.workpack_id = ? ORDER BY a.column_name
                """, String.class, wp.id());
        String worker = jdbc.queryForObject("SELECT id::text FROM bpa.worker WHERE employee_ref = 'E1021'", String.class);
        String line = jdbc.queryForObject("SELECT l.id::text FROM bpa.hour_journal_line l JOIN bpa.hour_journal_header h ON h.id = l.header_id WHERE h.workpack_id = ?",
                String.class, wp.id());

        assertThat(audit).containsExactlyInAnyOrder("activity_ref=A-2201 (old null) by production-team", "cbs_code_ref=CBS-139-PIP-01 (old null) by production-team",
                "cost_centre_ref=STRP01 (old null) by production-team", "journal_line_id=" + line + " (old null) by production-team",
                "project_ref=PRJ-139 (old null) by production-team", "submission_status=Draft (old null) by production-team",
                "wbs_ref=11031 (old null) by production-team", "worker_id=" + worker + " (old null) by production-team",
                "workpack_id=" + wp.id() + " (old null) by production-team");
        assertThat(jdbc.queryForList("""
                SELECT DISTINCT a.changed_at FROM bpa.audit_log a JOIN bpa.digital_timesheet t ON t.id = a.record_id
                WHERE a.table_name = 'digital_timesheet' AND t.workpack_id = ?""", java.sql.Timestamp.class, wp.id()))
                .as("one instant, from the injected clock (S9)").extracting(java.sql.Timestamp::toInstant).containsExactly(AssignmentFileApiTest.TODAY);
    }

    @Test
    void aBlankCbsCodeAndCostCentreWriteNoAuditRowForThoseColumns() {
        Work wp = ended(flow.awaiting("A-2299"), Map.of("E1150", 5));
        sheetsOf(wp, 1);

        assertThat(jdbc.queryForList("""
                SELECT a.column_name FROM bpa.audit_log a JOIN bpa.digital_timesheet t ON t.id = a.record_id
                WHERE a.table_name = 'digital_timesheet' AND t.workpack_id = ?""", String.class, wp.id()))
                .doesNotContain("cbs_code_ref", "cost_centre_ref").hasSize(7);
    }

    /** The coding is a copy taken when the time sheet is created and is not refreshed (data model 5.7). */
    @Test
    void theCostCentreIsASnapshot() {
        Work wp = ended(flow.awaiting(), Map.of("E1021", 40));
        sheetsOf(wp, 1);
        int audit = sheetAuditRows(wp);
        jdbc.update("UPDATE bpa.worker SET cost_centre_ref = 'STRP02' WHERE employee_ref = 'E1021'");
        try {
            JobClient client = Mockito.mock(JobClient.class, Mockito.RETURNS_DEEP_STUBS);
            worker.handle(client, job(83L, wp.instance()));
            List<JsonNode> sheets = sheetsOf(wp, 1);

            Mockito.verify(client).newCompleteCommand(83L);
            assertThat(sheets.get(0).path("costCentreRef").asText()).isEqualTo("STRP01");
            assertThat(sheetCount(wp)).isEqualTo(1);
            assertThat(sheetAuditRows(wp)).isEqualTo(audit);
        } finally {
            jdbc.update("UPDATE bpa.worker SET cost_centre_ref = 'STRP01' WHERE employee_ref = 'E1021'");
        }
    }

    /** A Work Pack that is not Assigning (or Assigned, for a re-run) is refused with its state in the text; nothing is written (D61). */
    @Test
    void aJobForAWorkPackThatIsNotAssigningFailsWithNoRetries() {
        Work wp = flow.draft();
        JobClient client = Mockito.mock(JobClient.class, Mockito.RETURNS_DEEP_STUBS);

        worker.handle(client, job(84L, wp.instance()));

        Mockito.verify(client.newFailCommand(84L)).retries(0);
        Mockito.verify(client.newFailCommand(84L).retries(0)).errorMessage(wp.number() + " is without an assignment state, not Assigning or Assigned, so its Digital Time sheets cannot be created (D61)");
        Mockito.verify(client, Mockito.never()).newCompleteCommand(Mockito.anyLong());
        assertThat(sheetCount(wp)).isZero();
    }

    /** A job creates time sheets only for its own Work Pack: another Work Pack's Line, whose time sheets were removed by hand, gets none (WHERE workpack_id). */
    @Test
    void aJobCreatesTimesheetsOnlyForItsOwnWorkPack() {
        Work mine = ended(flow.awaiting(), Map.of("E1021", 40));
        Work other = ended(flow.awaiting(), Map.of("E1099", 60));
        sheetsOf(mine, 1);
        sheetsOf(other, 1);
        jdbc.update("DELETE FROM bpa.audit_log WHERE table_name = 'digital_timesheet' AND record_id IN (SELECT id FROM bpa.digital_timesheet WHERE workpack_id = ?)", other.id());
        jdbc.update("DELETE FROM bpa.digital_timesheet WHERE workpack_id = ?", other.id());

        worker.handle(Mockito.mock(JobClient.class, Mockito.RETURNS_DEEP_STUBS), job(85L, mine.instance()));

        assertThat(sheetCount(other)).as("the other Work Pack got no time sheet from this job").isZero();
        assertThat(sheetCount(mine)).isEqualTo(1);
    }

    // --- helpers ---------------------------------------------------------------------------------------------------------------

    /** The Work Pack given an assignment file with these Workers and hours, all accepted by BPA-22, and run to the end of the process (BPA-14, BPA-15). */
    private Work ended(Work wp, Map<String, Number> hoursByBadge) {
        AssignmentFileBuilder file = AssignmentFileBuilder.assignment();
        hoursByBadge.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> file.row(wp.number(), e.getKey(), e.getValue()));
        Upload upload = flow.upload("production-team", "assign-sheets-" + wp.number() + ".xlsx", file.build());
        assertThat(settled(upload)).as("every row of the file is accepted").allMatch("Assigned"::equals);
        flow.awaitEnd(wp);
        return wp;
    }

    /** Waits until the Work Pack has this many time sheets and returns them as the API shows them to the supervisor. */
    private List<JsonNode> sheetsOf(Work wp, int expected) {
        return poll(() -> {
            HttpResponse<String> response = flow.loggedInAs("supervisor").get(TIMESHEETS + wp.number());
            return response.statusCode() == 200 ? elements(json(response.body())) : List.<JsonNode>of();
        }, sheets -> sheets.size() == expected, expected + " time sheet(s) of " + wp.number());
    }

    private List<String> settled(Upload upload) {
        assertThat(upload.status()).as("upload: " + upload.body()).isEqualTo(201);
        return poll(() -> elements(flow.result(upload).path("rows")).stream().map(r -> r.path("outcome").asText()).toList(),
                outcomes -> !outcomes.isEmpty() && !outcomes.contains("Pending"), "the rows of " + upload.id() + " answered");
    }

    private int sheetCount(Work wp) {
        return jdbc.queryForObject("SELECT count(*) FROM bpa.digital_timesheet WHERE workpack_id = ?", Integer.class, wp.id());
    }

    private int sheetCount(Work wp, String badge) {
        return jdbc.queryForObject("SELECT count(*) FROM bpa.digital_timesheet t JOIN bpa.worker w ON w.id = t.worker_id WHERE t.workpack_id = ? AND w.employee_ref = ?",
                Integer.class, wp.id(), badge);
    }

    /** Audit rows of this Work Pack's time sheets only (other Work Packs' late audit rows cannot move it). */
    private int sheetAuditRows(Work wp) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM bpa.audit_log a JOIN bpa.digital_timesheet t ON t.id = a.record_id
                WHERE a.table_name = 'digital_timesheet' AND t.workpack_id = ?""", Integer.class, wp.id());
    }

    private static ActivatedJob job(long key, long instance) {
        ActivatedJob job = Mockito.mock(ActivatedJob.class);
        Mockito.when(job.getKey()).thenReturn(key);
        Mockito.when(job.getProcessInstanceKey()).thenReturn(instance);
        Mockito.when(job.getElementId()).thenReturn(CREATE_TIMESHEETS);
        Mockito.when(job.getVariablesAsMap()).thenReturn(Map.of());
        return job;
    }
}
