package com.lcs.star.bpa;

import static com.lcs.star.bpa.AssignmentFlow.CATCH;
import static com.lcs.star.bpa.AssignmentFlow.activeIds;
import static com.lcs.star.bpa.CamundaSteps.elements;
import static com.lcs.star.bpa.CamundaSteps.json;
import static com.lcs.star.bpa.CamundaSteps.poll;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.lcs.star.bpa.AssignmentFlow.Upload;
import com.lcs.star.bpa.AssignmentFlow.Work;
import com.lcs.star.bpa.api.TestBrowser;
import com.lcs.star.bpa.worker.CreateHourJournalLinesWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import java.math.BigDecimal;
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
 * BPA-14: the Project Hour Journal Lines created by {@code create-hour-journal-lines} (ServiceTask_CreateHourJournalLines) and read by
 * {@code GET /api/v1/hour-journal-lines?workpackNumber=}. One test per Gherkin scenario, named as in the Jira DoD. Today is 30 Oct 2026
 * (Asia/Riyadh). "The assignment is saved" is the real path: a Work Pack at AwaitingFile (AssignmentFlow), an assignment file from the
 * AssignmentFileBuilder (never templates/demo; S18), BPA-22 validating its rows, and the instance moving on to this step. The next step,
 * ServiceTask_CreateDigitalTimesheets, is BPA-15 (it ends the process at EndEvent_WorkPackAssigned). The
 * readers, ppc-team and supervisor, are an ASSUMPTION in the story (09 S6); the empty list for a Work Pack without Lines is ours.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(AssignmentFileApiTest.TestClockConfig.class)
class HourJournalLineApiTest {

    static final String CREATE_LINES = "ServiceTask_CreateHourJournalLines";
    static final String LINES = "/api/v1/hour-journal-lines?workpackNumber=";

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
    CreateHourJournalLinesWorker worker;

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
    void BPA14_linesAreCreatedForAssignedWorkers() {
        Work wp = assigned(Map.of("E1021", 40, "E1099", 60));

        List<JsonNode> lines = linesOf(wp, 2);

        assertThat(lines).extracting(l -> l.path("employeeRef").asText() + "/" + l.path("role").asText() + "/" + l.path("designation").asText()
                + "/" + l.path("status").asText()).containsExactly("E1021/Welder/Grade 3/Open", "E1099/Fitter/Grade 2/Open");
        assertThat(hours(lines)).containsExactly("40", "60");
        flow.awaitEnd(wp); // BPA-15 runs through: the process ends at EndEvent_WorkPackAssigned
    }

    @Test
    void BPA14_rejectedRowsGetNoLine() {
        Work wp = flow.awaiting();
        Upload upload = flow.upload("production-team", "assign-lines-rejected.xlsx",
                AssignmentFileBuilder.assignment().row(wp.number(), "E1021", 40).row(wp.number(), "E9999", 20).build());
        assertThat(settled(upload)).containsExactly("Assigned", "Worker not in worker master data");

        List<JsonNode> lines = linesOf(wp, 1);

        assertThat(lines).extracting(l -> l.path("employeeRef").asText()).containsExactly("E1021");
        flow.awaitEnd(wp); // the step has finished, so a second Line could no longer arrive
        assertThat(linesUnderHeader(wp)).as("one Line under the Header, none for E9999 (it is not in the Worker master, so it has no assignment)").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.hour_journal_line l JOIN bpa.worker w ON w.id = l.worker_id WHERE w.employee_ref = 'E9999'",
                Integer.class)).as("no Line exists for E9999").isZero();
    }

    @Test
    void BPA14_decimalHoursAreKeptExactly() {
        Work wp = assigned(Map.of("E1150", 0.01));

        List<JsonNode> lines = linesOf(wp, 1);

        assertThat(lines.get(0).path("employeeRef").asText()).isEqualTo("E1150");
        assertThat(hours(lines)).containsExactly("0.01");
        assertThat(jdbc.queryForObject("SELECT l.estimated_hours::text FROM bpa.hour_journal_line l JOIN bpa.hour_journal_header h ON h.id = l.header_id WHERE h.workpack_id = ?",
                String.class, wp.id())).isEqualTo("0.01");
    }

    @Test
    void BPA14_rerunDoesNotDuplicateLines() {
        Work wp = assigned(Map.of("E1021", 40, "E1099", 60));
        linesOf(wp, 2);
        int audit = lineAuditRows(wp);
        JobClient client = Mockito.mock(JobClient.class, Mockito.RETURNS_DEEP_STUBS);

        worker.handle(client, job(61L, wp.instance()));
        worker.handle(client, job(62L, wp.instance()));

        Mockito.verify(client).newCompleteCommand(61L);
        Mockito.verify(client).newCompleteCommand(62L);
        assertThat(lineCount(wp, "E1021")).as("exactly one Line for E1021 under the Header").isEqualTo(1);
        assertThat(lineCount(wp, "E1099")).as("and one for E1099").isEqualTo(1);
        assertThat(lineAuditRows(wp)).as("the re-run adds no audit row").isEqualTo(audit);
    }

    @Test
    void BPA14_unknownWorkPackReturnsNotFound() {
        HttpResponse<String> response = flow.loggedInAs("ppc-team").get(LINES + "WP-999999");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json(response.body()).path("detail").asText()).isEqualTo("Work Pack not found");
    }

    @Test
    void BPA14_otherRolesCannotReadLines() {
        Work wp = assigned(Map.of("E1021", 40));
        linesOf(wp, 1);

        HttpResponse<String> response = flow.loggedInAs("production-planner").get(LINES + wp.number());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).as("no Line data is returned").doesNotContain("employeeRef").doesNotContain("Welder");
    }

    // --- not scenarios ---------------------------------------------------------------------------------------------------------

    /** The supervisor is the other reader (ASSUMPTION in the story); anyone else, anonymous callers and HEAD follow the same rule (the BPA-10 lesson). */
    @Test
    void supervisorReadsAndHeadAndAnonymousFollowTheRule() {
        Work wp = assigned(Map.of("E1021", 40));
        linesOf(wp, 1);

        HttpResponse<String> supervisor = flow.loggedInAs("supervisor").get(LINES + wp.number());
        assertThat(supervisor.statusCode()).isEqualTo(200);
        assertThat(elements(json(supervisor.body()))).hasSize(1);
        assertThat(flow.loggedInAs("production-planner").head(LINES + wp.number()).statusCode()).as("HEAD is not a way round").isEqualTo(403);
        assertThat(flow.loggedInAs("production-team").get(LINES + wp.number()).statusCode()).isEqualTo(403);
        assertThat(new TestBrowser(port).get(LINES + wp.number()).statusCode()).isEqualTo(401);
        // a trailing slash is not a way round the rule: it is no route, so it is never a 200 with data
        HttpResponse<String> slash = flow.loggedInAs("production-planner").get("/api/v1/hour-journal-lines/?workpackNumber=" + wp.number());
        assertThat(slash.statusCode()).isNotEqualTo(200);
        assertThat(slash.body()).doesNotContain("employeeRef");
    }

    /** The Lines come back by Badge No, not in the order the file listed the Workers. */
    @Test
    void linesAreOrderedByBadgeNo() {
        Work wp = flow.awaiting();
        Upload upload = flow.upload("production-team", "assign-lines-order.xlsx", AssignmentFileBuilder.assignment()
                .row(wp.number(), "E1150", 5).row(wp.number(), "E1099", 60).row(wp.number(), "E1021", 40).build());
        assertThat(settled(upload)).allMatch("Assigned"::equals);

        assertThat(linesOf(wp, 3)).extracting(l -> l.path("employeeRef").asText()).containsExactly("E1021", "E1099", "E1150");
    }

    /** A job creates Lines only for its own Work Pack: another Work Pack's assignments stay without Lines (WHERE workpack_id). */
    @Test
    void aJobCreatesLinesOnlyForItsOwnWorkPack() {
        Work mine = assigned(Map.of("E1021", 40));
        Work other = assigned(Map.of("E1099", 60));
        linesOf(mine, 1);
        linesOf(other, 1);
        flow.awaitEnd(other);
        // by hand: the other Work Pack is assigned but has no Lines (as if its job had not run yet); its time sheets (BPA-15) reference the Lines, so they go first
        jdbc.update("DELETE FROM bpa.audit_log WHERE table_name = 'digital_timesheet' AND record_id IN (SELECT id FROM bpa.digital_timesheet WHERE workpack_id = ?)", other.id());
        jdbc.update("DELETE FROM bpa.digital_timesheet WHERE workpack_id = ?", other.id());
        jdbc.update("DELETE FROM bpa.audit_log WHERE table_name = 'hour_journal_line' AND record_id IN (SELECT l.id FROM bpa.hour_journal_line l JOIN bpa.hour_journal_header h ON h.id = l.header_id WHERE h.workpack_id = ?)", other.id());
        jdbc.update("DELETE FROM bpa.hour_journal_line WHERE header_id IN (SELECT id FROM bpa.hour_journal_header WHERE workpack_id = ?)", other.id());

        worker.handle(Mockito.mock(JobClient.class, Mockito.RETURNS_DEEP_STUBS), job(72L, mine.instance()));

        assertThat(linesUnderHeader(other)).as("the other Work Pack's assignment got no Line from this job").isZero();
        assertThat(lineAuditRows(other)).isZero();
        assertThat(linesUnderHeader(mine)).isEqualTo(1);
    }

    /** No Header (BPA-7 makes it before any assignment): the job fails with no retries and says why; nothing is written (the story's SOR: the Line hangs off the Header). */
    @Test
    void aWorkPackWithoutAHeaderFailsTheJobWithNoRetries() {
        Work wp = flow.draft();
        jdbc.update("DELETE FROM bpa.hour_journal_header WHERE workpack_id = ?", wp.id()); // by hand: no process path leaves a Work Pack without one
        JobClient client = Mockito.mock(JobClient.class, Mockito.RETURNS_DEEP_STUBS);

        worker.handle(client, job(73L, wp.instance()));

        Mockito.verify(client.newFailCommand(73L)).retries(0);
        Mockito.verify(client.newFailCommand(73L).retries(0)).errorMessage(wp.number() + " has no Project Hour Journal Header, so its Lines cannot be created (BPA-7)");
        Mockito.verify(client, Mockito.never()).newCompleteCommand(Mockito.anyLong());
        assertThat(lineAuditRows(wp)).isZero();
    }

    @Test
    void aMalformedOrMissingWorkpackNumberIs400() {
        TestBrowser ppc = flow.loggedInAs("ppc-team");

        HttpResponse<String> malformed = ppc.get(LINES + "WP-12");
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(json(malformed.body()).path("detail").asText()).isEqualTo("Workpack number must match WP-######");
        assertThat(ppc.get("/api/v1/hour-journal-lines").statusCode()).isEqualTo(400);
    }

    /** A Work Pack whose Header exists but has no assignment yet has no Lines: 200 with an empty list (ASSUMPTION, as BPA-7 for the Header). */
    @Test
    void aWorkPackWithoutLinesYetAnswersAnEmptyList() {
        Work wp = flow.awaiting();

        HttpResponse<String> response = flow.loggedInAs("ppc-team").get(LINES + wp.number());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(elements(json(response.body()))).isEmpty();
    }

    /** Every business column of a new Line is audited, by the uploader, once (D64, data model 6.1; D63). */
    @Test
    void aLineIsAuditedByTheUploader() {
        Work wp = assigned(Map.of("E1021", 40));
        linesOf(wp, 1);

        List<String> audit = jdbc.queryForList("""
                SELECT a.column_name || '=' || coalesce(a.new_value, 'null') || ' (old ' || coalesce(a.old_value, 'null') || ') by ' || a.username
                FROM bpa.audit_log a JOIN bpa.hour_journal_line l ON l.id = a.record_id JOIN bpa.hour_journal_header h ON h.id = l.header_id
                WHERE a.table_name = 'hour_journal_line' AND h.workpack_id = ? ORDER BY a.column_name
                """, String.class, wp.id());

        String header = jdbc.queryForObject("SELECT id::text FROM bpa.hour_journal_header WHERE workpack_id = ?", String.class, wp.id());
        String worker = jdbc.queryForObject("SELECT id::text FROM bpa.worker WHERE employee_ref = 'E1021'", String.class);
        assertThat(audit).containsExactlyInAnyOrder("designation=Grade 3 (old null) by production-team", "estimated_hours=40 (old null) by production-team",
                "header_id=" + header + " (old null) by production-team", "role=Welder (old null) by production-team",
                "status=Open (old null) by production-team", "worker_id=" + worker + " (old null) by production-team");
        assertThat(jdbc.queryForList("""
                SELECT DISTINCT a.changed_at FROM bpa.audit_log a JOIN bpa.hour_journal_line l ON l.id = a.record_id
                JOIN bpa.hour_journal_header h ON h.id = l.header_id WHERE a.table_name = 'hour_journal_line' AND h.workpack_id = ?""",
                java.sql.Timestamp.class, wp.id())).as("one instant, from the injected clock (S9)").extracting(java.sql.Timestamp::toInstant)
                .containsExactly(AssignmentFileApiTest.TODAY);
    }

    /** Role and designation are copies taken when the Line is created and never refreshed (data model N3, C14). */
    @Test
    void roleAndDesignationAreASnapshot() {
        Work wp = assigned(Map.of("E1021", 40));
        linesOf(wp, 1);
        int audit = lineAuditRows(wp);
        Map<String, Object> original = jdbc.queryForMap("SELECT role, designation FROM bpa.worker WHERE employee_ref = 'E1021'");
        jdbc.update("UPDATE bpa.worker SET role = 'Foreman', designation = 'Grade 9' WHERE employee_ref = 'E1021'");
        try {
            // the master changed and the job runs again (a retry): the Line keeps what it was created with, and nothing is written
            JobClient client = Mockito.mock(JobClient.class, Mockito.RETURNS_DEEP_STUBS);
            worker.handle(client, job(71L, wp.instance()));
            List<JsonNode> lines = linesOf(wp, 1);

            Mockito.verify(client).newCompleteCommand(71L);
            assertThat(lines.get(0).path("role").asText()).isEqualTo("Welder");
            assertThat(lines.get(0).path("designation").asText()).isEqualTo("Grade 3");
            assertThat(hours(lines)).containsExactly("40");
            assertThat(lineCount(wp, "E1021")).isEqualTo(1);
            assertThat(lineAuditRows(wp)).isEqualTo(audit);
        } finally {
            jdbc.update("UPDATE bpa.worker SET role = ?, designation = ? WHERE employee_ref = 'E1021'", original.get("role"), original.get("designation"));
        }
    }

    // --- helpers ---------------------------------------------------------------------------------------------------------------

    /** A Work Pack at AwaitingFile that has been given an assignment file with these Workers and hours, all accepted by BPA-22. */
    private Work assigned(Map<String, Number> hoursByBadge) {
        Work wp = flow.awaiting();
        AssignmentFileBuilder file = AssignmentFileBuilder.assignment();
        hoursByBadge.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> file.row(wp.number(), e.getKey(), e.getValue()));
        Upload upload = flow.upload("production-team", "assign-lines-" + wp.number() + ".xlsx", file.build());
        assertThat(settled(upload)).as("every row of the file is accepted").allMatch("Assigned"::equals);
        return wp;
    }

    /** Waits until the Work Pack has this many Lines (the step runs after BPA-22 answered) and returns them as the API shows them to ppc-team. */
    private List<JsonNode> linesOf(Work wp, int expected) {
        return poll(() -> {
            HttpResponse<String> response = flow.loggedInAs("ppc-team").get(LINES + wp.number());
            return response.statusCode() == 200 ? elements(json(response.body())) : List.<JsonNode>of();
        }, lines -> lines.size() == expected, expected + " Line(s) of " + wp.number());
    }

    private static List<String> hours(List<JsonNode> lines) {
        return lines.stream().map(l -> new BigDecimal(l.path("estimatedHours").asText()).stripTrailingZeros().toPlainString()).toList();
    }

    private List<String> settled(Upload upload) {
        assertThat(upload.status()).as("upload: " + upload.body()).isEqualTo(201);
        return poll(() -> elements(flow.result(upload).path("rows")).stream().map(r -> r.path("outcome").asText()).toList(),
                outcomes -> !outcomes.isEmpty() && !outcomes.contains("Pending"), "the rows of " + upload.id() + " answered");
    }

    private int lineCount(Work wp, String badge) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM bpa.hour_journal_line l JOIN bpa.hour_journal_header h ON h.id = l.header_id
                JOIN bpa.worker w ON w.id = l.worker_id WHERE h.workpack_id = ? AND w.employee_ref = ?""", Integer.class, wp.id(), badge);
    }

    /** Lines under the Work Pack's Header, whatever the API shows. */
    private int linesUnderHeader(Work wp) {
        return jdbc.queryForObject("SELECT count(*) FROM bpa.hour_journal_line l JOIN bpa.hour_journal_header h ON h.id = l.header_id WHERE h.workpack_id = ?",
                Integer.class, wp.id());
    }

    /** Audit rows of this Work Pack's Lines only (other Work Packs' late audit rows cannot move it). */
    private int lineAuditRows(Work wp) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM bpa.audit_log a JOIN bpa.hour_journal_line l ON l.id = a.record_id
                JOIN bpa.hour_journal_header h ON h.id = l.header_id WHERE a.table_name = 'hour_journal_line' AND h.workpack_id = ?""",
                Integer.class, wp.id());
    }

    private static ActivatedJob job(long key, long instance) {
        ActivatedJob job = Mockito.mock(ActivatedJob.class);
        Mockito.when(job.getKey()).thenReturn(key);
        Mockito.when(job.getProcessInstanceKey()).thenReturn(instance);
        Mockito.when(job.getElementId()).thenReturn(CREATE_LINES);
        Mockito.when(job.getVariablesAsMap()).thenReturn(Map.of());
        return job;
    }
}
