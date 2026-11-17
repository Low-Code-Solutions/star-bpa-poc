package com.lcs.star.bpa;

import static com.lcs.star.bpa.AssignmentFlow.CATCH;
import static com.lcs.star.bpa.AssignmentFlow.END_NOT_APPLIED;
import static com.lcs.star.bpa.AssignmentFlow.END_REPLACED;
import static com.lcs.star.bpa.CamundaSteps.elements;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.lcs.star.bpa.AssignmentFlow.Upload;
import com.lcs.star.bpa.AssignmentFlow.Work;
import com.lcs.star.bpa.adapter.CamundaProcessClient;
import com.lcs.star.bpa.adapter.CamundaTaskClient.EngineUnavailableException;
import com.lcs.star.bpa.worker.ReplaceWorkerAssignmentWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BPA-13: replacing a Work Pack's Worker assignment with a new file (message AssignmentFileReplaced, Process_PPC_WorkerReassignment,
 * {@code replace-worker-assignment}). One test per Gherkin scenario, named as in the Jira DoD. The Background is real: a Work Pack is taken
 * to Assigned the way BPA-12, BPA-22, BPA-14 and BPA-15 take it, with E1021 (40 h) and E1099 (60 h) (AssignmentFlow.assignedWorkPack), and
 * replacement files come from the AssignmentFileBuilder (never templates/demo; S18). Nothing in a replacement reads the date, so the clock
 * stays at 30 Oct 2026 (the AC says 03 Nov). "Hours loaded" is seeded by inserting a digital_timesheet_line by hand, as the story says
 * (Epic 1 defines the table, Epic 2 fills it). The rule for the previous assignment, Journal Lines and time sheets is in the plan
 * (BPA-13, "How the previous assignment is replaced"). Every count is scoped to its own Work Pack: other Work Packs' jobs write at the same time.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(AssignmentFileApiTest.TestClockConfig.class)
class WorkerReplacementApiTest {

    static final String REPLACE_TASK = "ServiceTask_ReplaceAssignment";
    static final String NOT_APPLIED = "Replacement not applied: file has rejected rows";

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
    ReplaceWorkerAssignmentWorker worker;

    /** The engine call is real everywhere except in the one scenario that makes it fail (D75). */
    @MockitoSpyBean
    CamundaProcessClient camunda;

    private AssignmentFlow flow;

    /** All test classes share one Camunda and Work Pack numbers repeat: an instance another class left waiting could take our message, or hold our key. */
    @BeforeAll
    static void noInstanceLeftByAnotherClass() {
        IntegrationApp.background();
        CamundaSteps.cancelInstancesWaitingAt(CATCH);
        CamundaSteps.cancelInstancesWaitingAt(AssignmentFlow.BOOK_HOURS); // BPA-49: the Epic 2 instance the end event now starts
        CamundaSteps.cancelInstancesWaitingAt(REPLACE_TASK);
    }

    @BeforeEach
    void today() {
        clock.set(AssignmentFileApiTest.TODAY);
        flow = new AssignmentFlow(port, jdbc);
    }

    // --- the scenarios ----------------------------------------------------------------------------------------------------

    @Test
    void BPA13_newFileReplacesAssignment() {
        Work wp = flow.assignedWorkPack(Map.of("E1021", 40, "E1099", 60));
        Upload upload = replace(wp, "replace-01.xlsx", row(wp, "E1021", 50), row(wp, "E1150", 30));

        assertThat(flow.settled(upload)).containsExactly("Assigned", "Assigned");
        flow.awaitReassignmentEnd(wp, END_REPLACED);

        assertThat(assignments(wp)).containsExactly("E1021=50", "E1150=30");
        assertThat(history(wp, upload)).containsExactlyInAnyOrder("E1021 40->50 Replaced replace-01.xlsx", "E1150 0->30 Added replace-01.xlsx",
                "E1099 60->0 Removed replace-01.xlsx");
        assertThat(flow.state(wp)).isEqualTo("Assigned");
    }

    @Test
    void BPA13_removedWorkerWithHoursIsKeptAndFlagged() {
        Work wp = flow.assignedWorkPack(Map.of("E1021", 40, "E1099", 60));
        seedHoursLoaded(wp, "E1099", "8");
        Upload upload = replace(wp, "replace-02.xlsx", row(wp, "E1021", 40), row(wp, "E1150", 30));

        flow.settled(upload);
        flow.awaitReassignmentEnd(wp, END_REPLACED);

        assertThat(assignments(wp)).as("E1150 is added, E1099 stays with its 60 hours").containsExactly("E1021=40", "E1099=60", "E1150=30");
        assertThat(flag(wp, "E1099")).isEqualTo("HoursLoadedNotRemoved");
        assertThat(flag(wp, "E1021")).isNull();
        assertThat(elements(flow.result(upload).path("rows"))).as("the result shows E1099 as \"Hours loaded; not removed\"")
                .anySatisfy(r -> {
                    assertThat(r.path("badgeNo").asText()).isEqualTo("E1099");
                    assertThat(r.path("outcome").asText()).isEqualTo("Hours loaded; not removed");
                    assertThat(r.has("rowNumber")).as("a row with no row number (F1)").isFalse();
                });
        assertThat(history(wp, upload)).as("E1021 is unchanged (no history), E1099 is not removed (no Removed row)")
                .containsExactly("E1150 0->30 Added replace-02.xlsx");
        assertThat(line(wp, "E1099")).as("the kept Worker's Journal Line is left alone").isEqualTo("Open 60");
        assertThat(sheet(wp, "E1099")).as("and so is his time sheet").isEqualTo("Draft");
        assertThat(flagAudit(wp)).as("the flag is audited (D64)").containsExactly("null->HoursLoadedNotRemoved by production-team");
    }

    /** Q3: a Worker kept because hours are loaded who is listed again is updated like any other, and the flag stays (data model B14). */
    @Test
    void aFlaggedWorkerListedAgainIsUpdatedAndKeepsTheFlag() {
        Work wp = flow.assignedWorkPack(Map.of("E1021", 40, "E1099", 60));
        seedHoursLoaded(wp, "E1099", "8");
        flow.settled(replace(wp, "replace-15-a.xlsx", row(wp, "E1021", 40), row(wp, "E1150", 30)));
        flow.awaitReassignmentEnd(wp, END_REPLACED, 1);
        assertThat(flag(wp, "E1099")).isEqualTo("HoursLoadedNotRemoved");

        Upload second = replace(wp, "replace-15-b.xlsx", row(wp, "E1021", 40), row(wp, "E1099", 70), row(wp, "E1150", 30));
        flow.settled(second);
        flow.awaitReassignmentEnd(wp, END_REPLACED, 2);

        assertThat(assignments(wp)).containsExactly("E1021=40", "E1099=70", "E1150=30");
        assertThat(flag(wp, "E1099")).as("the flag stays").isEqualTo("HoursLoadedNotRemoved");
        assertThat(history(wp, second)).containsExactly("E1099 60->70 Replaced replace-15-b.xlsx");
        assertThat(line(wp, "E1099")).isEqualTo("Open 70");
    }

    /** Q2: "hours loaded" is a line with hours above 0: a time sheet with only a 0-hour line is empty, so its Worker is removed and the sheet Cancelled. */
    @Test
    void aTimesheetWithOnlyZeroHourLinesDoesNotCountAsHoursLoaded() {
        Work wp = flow.assignedWorkPack(Map.of("E1021", 40, "E1099", 60));
        seedHoursLoaded(wp, "E1099", "0");

        flow.settled(replace(wp, "replace-16.xlsx", row(wp, "E1021", 40)));
        flow.awaitReassignmentEnd(wp, END_REPLACED, 1);

        assertThat(assignments(wp)).as("E1099 is removed").containsExactly("E1021=40");
        assertThat(sheet(wp, "E1099")).isEqualTo("Cancelled");
        assertThat(line(wp, "E1099")).startsWith("Closed");
    }

    /** Q1: reopening a Line and a time sheet does not refresh what was copied when they were created (data model N3, 5.7). */
    @Test
    void reopeningDoesNotRefreshTheCopiedRoleDesignationOrCostCentre() {
        Work wp = flow.assignedWorkPack(Map.of("E1021", 40, "E1099", 60));
        flow.settled(replace(wp, "replace-17-remove.xlsx", row(wp, "E1021", 40)));
        flow.awaitReassignmentEnd(wp, END_REPLACED, 1);
        Map<String, Object> original = jdbc.queryForMap("SELECT role, designation, cost_centre_ref FROM bpa.worker WHERE employee_ref = 'E1099'");
        jdbc.update("UPDATE bpa.worker SET role = 'Foreman', designation = 'Grade 9', cost_centre_ref = 'STRP02' WHERE employee_ref = 'E1099'"); // by hand
        try {
            flow.settled(replace(wp, "replace-17-readd.xlsx", row(wp, "E1021", 40), row(wp, "E1099", 25)));
            flow.awaitReassignmentEnd(wp, END_REPLACED, 2);

            assertThat(jdbc.queryForObject("""
                    SELECT l.role || '/' || l.designation FROM bpa.hour_journal_line l JOIN bpa.hour_journal_header h ON h.id = l.header_id
                    JOIN bpa.worker w ON w.id = l.worker_id WHERE h.workpack_id = ? AND w.employee_ref = 'E1099'""", String.class, wp.id()))
                    .as("the Line keeps its copied role and designation").isEqualTo(original.get("role") + "/" + original.get("designation"));
            assertThat(jdbc.queryForObject("""
                    SELECT t.cost_centre_ref FROM bpa.digital_timesheet t JOIN bpa.worker w ON w.id = t.worker_id WHERE t.workpack_id = ? AND w.employee_ref = 'E1099'""",
                    String.class, wp.id())).as("and the time sheet its copied cost centre").isEqualTo(original.get("cost_centre_ref"));
        } finally {
            jdbc.update("UPDATE bpa.worker SET role = ?, designation = ?, cost_centre_ref = ? WHERE employee_ref = 'E1099'", original.get("role"),
                    original.get("designation"), original.get("cost_centre_ref"));
        }
    }

    @Test
    void BPA13_replacementWithRejectedRowIsNotApplied() {
        Work wp = flow.assignedWorkPack(Map.of("E1021", 40, "E1099", 60));
        Upload upload = replace(wp, "replace-03.xlsx", row(wp, "E1021", 50), row(wp, "E9999", 20));

        flow.settled(upload);
        flow.awaitReassignmentEnd(wp, END_NOT_APPLIED);

        List<JsonNode> rows = elements(flow.result(upload).path("rows"));
        assertThat(rows).filteredOn(r -> "E9999".equals(r.path("badgeNo").asText())).singleElement()
                .satisfies(r -> assertThat(r.path("outcome").asText()).isEqualTo("Worker not in worker master data"));
        assertThat(rows).filteredOn(r -> r.has("rowNumber")).extracting(r -> r.path("wpOutcome").asText()).as("the Work Pack's message is on every row")
                .containsOnly(NOT_APPLIED);
        assertThat(rows).filteredOn(r -> "E1021".equals(r.path("badgeNo").asText())).singleElement()
                .satisfies(r -> assertThat(r.has("outcome")).as("a valid row of a Work Pack not applied has no outcome of its own (B1)").isFalse());
        assertThat(assignments(wp)).as("the assignment is unchanged").containsExactly("E1021=40", "E1099=60");
        assertThat(flow.state(wp)).isEqualTo("Assigned");
    }

    @Test
    void BPA13_replacementHoursBoundary() {
        Work wp = flow.assignedWorkPack(Map.of("E1021", 40, "E1099", 60));

        Upload zero = replace(wp, "replace-04-zero.xlsx", row(wp, "E1021", 0), row(wp, "E1099", 60));
        flow.settled(zero);
        flow.awaitReassignmentEnd(wp, END_NOT_APPLIED);
        assertThat(elements(flow.result(zero).path("rows"))).filteredOn(r -> "E1021".equals(r.path("badgeNo").asText())).singleElement()
                .satisfies(r -> assertThat(r.path("outcome").asText()).isEqualTo("Estimated hours must be greater than 0"));
        assertThat(assignments(wp)).as("0 hours: not applied").containsExactly("E1021=40", "E1099=60");

        Upload smallest = replace(wp, "replace-04-smallest.xlsx", row(wp, "E1021", 0.01), row(wp, "E1099", 60));
        assertThat(flow.settled(smallest)).containsExactly("Assigned", "Assigned");
        flow.awaitReassignmentEnd(wp, END_REPLACED);
        assertThat(assignments(wp)).as("0.01 hours: applied").containsExactly("E1021=0.01", "E1099=60");
    }

    @Test
    void BPA13_replacementWhileAssignmentInProgressIsRejected() {
        Work wp = flow.assignedWorkPack(Map.of("E1021", 40, "E1099", 60));
        jdbc.update("UPDATE bpa.workpack SET assignment_state = 'Assigning' WHERE id = ?", wp.id()); // by hand: since BPA-15 no process path rests here
        byte[] file = file(row(wp, "E1021", 50));

        Upload upload = flow.upload("production-team", "replace-05.xlsx", file);

        assertThat(AssignmentFlow.outcomes(upload)).containsExactly("Assignment still in progress; try again later");
        assertThat(flow.claims(AssignmentFlow.sha256(file), wp.number())).as("nothing is published").isZero();
        CamundaSteps.assertStaysEmpty(() -> flow.reassignmentInstances(wp.number()), "a Process_PPC_WorkerReassignment instance for " + wp.number());
    }

    @Test
    void BPA13_concurrentReplacementIsRejected() {
        Work wp = flow.assignedWorkPack(Map.of("E1021", 40, "E1099", 60));
        jdbc.update("UPDATE bpa.workpack SET assignment_state = 'Replacing' WHERE id = ?", wp.id()); // by hand: a replacement is running
        byte[] file = file(row(wp, "E1021", 50));

        Upload upload = flow.upload("production-team", "replace-06.xlsx", file);

        assertThat(AssignmentFlow.outcomes(upload)).containsExactly("Replacement already in progress");
        assertThat(flow.claims(AssignmentFlow.sha256(file), wp.number())).isZero();
        CamundaSteps.assertStaysEmpty(() -> flow.reassignmentInstances(wp.number()), "a second Process_PPC_WorkerReassignment instance for " + wp.number());
    }

    @Test
    void BPA13_replacementStartFailureReverts() {
        Work wp = flow.assignedWorkPack(Map.of("E1021", 40, "E1099", 60));
        Mockito.doThrow(new EngineUnavailableException("Camunda cannot be reached")).when(camunda)
                .correlate(Mockito.eq("AssignmentFileReplaced"), Mockito.anyString(), Mockito.anyMap());
        byte[] file = file(row(wp, "E1021", 50), row(wp, "E1150", 30));

        Upload upload = flow.upload("production-team", "replace-07.xlsx", file);

        assertThat(AssignmentFlow.outcomes(upload)).containsExactly("Replacement could not be started; try again", "Replacement could not be started; try again");
        assertThat(assignments(wp)).as("the assignment is unchanged").containsExactly("E1021=40", "E1099=60");
        assertThat(flow.state(wp)).isEqualTo("Assigned");
        assertThat(flow.claims(AssignmentFlow.sha256(file), wp.number())).as("the claim is undone, so the file can be sent again").isZero();
        assertThat(stateChanges(wp)).as("Assigned -> Replacing and back are both audited (D64)").contains("Assigned->Replacing by production-team", "Replacing->Assigned by production-team");
    }

    @Test
    void BPA13_linesAndTimesheetsFollowNewFile() {
        Work wp = flow.assignedWorkPack(Map.of("E1021", 40, "E1099", 60));
        Upload upload = replace(wp, "replace-08.xlsx", row(wp, "E1021", 50), row(wp, "E1150", 30));
        flow.settled(upload);
        flow.awaitReassignmentEnd(wp, END_REPLACED);

        assertThat(line(wp, "E1150")).as("E1150: an Open Journal Line with 30 hours").isEqualTo("Open 30");
        assertThat(sheet(wp, "E1150")).as("and a Draft Digital Time sheet").isEqualTo("Draft");
        assertThat(line(wp, "E1021")).as("E1021's Journal Line shows 50 hours").isEqualTo("Open 50");
        assertThat(sheet(wp, "E1021")).as("its time sheet is left alone").isEqualTo("Draft");
        assertThat(line(wp, "E1099")).as("E1099's Journal Line is Closed").startsWith("Closed");
        assertThat(sheet(wp, "E1099")).as("and its empty Draft time sheet is Cancelled").isEqualTo("Cancelled");
    }

    @Test
    void BPA13_rerunIsIdempotent() {
        Work wp = flow.assignedWorkPack(Map.of("E1021", 40, "E1099", 60));
        Upload upload = replace(wp, "replace-09.xlsx", row(wp, "E1021", 50), row(wp, "E1150", 30));
        flow.settled(upload);
        flow.awaitReassignmentEnd(wp, END_REPLACED);
        List<String> assignmentsBefore = assignments(wp);
        int historyBefore = historyCount(wp);
        int auditBefore = scopedAuditRows(wp);
        JobClient client = Mockito.mock(JobClient.class, Mockito.RETURNS_DEEP_STUBS);

        worker.handle(client, job(91L, wp, upload));
        worker.handle(client, job(92L, wp, upload));

        Mockito.verify(client.newCompleteCommand(91L)).variables(Map.of("replacementApplied", true));
        Mockito.verify(client.newCompleteCommand(92L)).variables(Map.of("replacementApplied", true));
        assertThat(assignments(wp)).isEqualTo(assignmentsBefore);
        for (String badge : List.of("E1021", "E1150")) {
            assertThat(lineCount(wp, badge)).as("exactly one Journal Line for " + badge).isEqualTo(1);
            assertThat(sheetCount(wp, badge)).as("and one time sheet for " + badge).isEqualTo(1);
        }
        assertThat(historyCount(wp)).as("the history has no duplicate entries").isEqualTo(historyBefore);
        assertThat(scopedAuditRows(wp)).as("and the re-run writes no audit row").isEqualTo(auditBefore);
        assertThat(flow.state(wp)).isEqualTo("Assigned");
    }

    @Test
    void BPA13_rejectedRowBlocksOnlyItsWorkPack() {
        Work a = flow.assignedWorkPack(Map.of("E1021", 40, "E1099", 60));
        Work b = flow.assignedWorkPack(Map.of("E1021", 20));
        Upload upload = flow.upload("production-team", "replace-10.xlsx", AssignmentFileBuilder.assignment()
                .row(a.number(), "E1021", 50).row(b.number(), "E9999", 10).build());

        flow.settled(upload);
        flow.awaitReassignmentEnd(a, END_REPLACED);
        flow.awaitReassignmentEnd(b, END_NOT_APPLIED);

        assertThat(assignments(a)).as("the replacement for the first Work Pack is applied").contains("E1021=50");
        List<JsonNode> rows = elements(flow.result(upload).path("rows"));
        assertThat(rows).filteredOn(r -> b.number().equals(r.path("workpackNumber").asText())).singleElement().satisfies(r -> {
            assertThat(r.path("outcome").asText()).isEqualTo("Worker not in worker master data");
            assertThat(r.path("wpOutcome").asText()).isEqualTo(NOT_APPLIED);
        });
        assertThat(rows).filteredOn(r -> a.number().equals(r.path("workpackNumber").asText())).singleElement()
                .satisfies(r -> assertThat(r.has("wpOutcome")).as("the first Work Pack carries no Work Pack message").isFalse());
        assertThat(assignments(b)).as("the second Work Pack is unchanged").containsExactly("E1021=20");
    }

    @Test
    void BPA13_reAddedWorkerReopensLineAndTimesheet() {
        Work wp = flow.assignedWorkPack(Map.of("E1021", 40, "E1099", 60));
        Upload removal = replace(wp, "replace-11-remove.xlsx", row(wp, "E1021", 40));
        flow.settled(removal);
        flow.awaitReassignmentEnd(wp, END_REPLACED);
        assertThat(line(wp, "E1099")).startsWith("Closed");
        assertThat(sheet(wp, "E1099")).isEqualTo("Cancelled");

        Upload back = replace(wp, "replace-11-readd.xlsx", row(wp, "E1021", 40), row(wp, "E1099", 25));
        flow.settled(back);
        flow.awaitReassignmentEnd(wp, END_REPLACED, 2); // the second replacement itself, not the first one's end

        assertThat(assignments(wp)).containsExactly("E1021=40", "E1099=25");
        assertThat(lineCount(wp, "E1099")).as("exactly one Journal Line for E1099").isEqualTo(1);
        assertThat(line(wp, "E1099")).as("Open with 25 hours").isEqualTo("Open 25");
        assertThat(sheetCount(wp, "E1099")).as("exactly one Digital Time sheet for E1099").isEqualTo(1);
        assertThat(sheet(wp, "E1099")).isEqualTo("Draft");
        assertThat(history(wp, back)).containsExactly("E1099 0->25 Added replace-11-readd.xlsx");
    }

    // --- not scenarios ---------------------------------------------------------------------------------------------------------

    /** The same bytes again after a replacement are the duplicate of D73: no new replacement starts and the file-level line says so. */
    @Test
    void theSameReplacementFileAgainIsAlreadyProcessed() {
        Work wp = flow.assignedWorkPack(Map.of("E1021", 40, "E1099", 60));
        byte[] file = file(row(wp, "E1021", 50));
        Upload first = flow.upload("production-team", "replace-12.xlsx", file);
        flow.settled(first);
        flow.awaitReassignmentEnd(wp, END_REPLACED);

        Upload again = flow.upload("production-team", "replace-12-copy.xlsx", file);

        assertThat(again.body().path("outcome").asText()).isEqualTo("File already processed");
        for (int i = 0; i < 4; i++) {
            CamundaSteps.pause(); // the variable search is eventually consistent: give a wrongly started second instance time to show
        }
        assertThat(flow.reassignmentInstances(wp.number())).as("no second replacement started").hasSize(1);
    }

    /** The state moves Assigned -> Replacing -> Assigned, each audited by the uploader (D61, D64), and the changed hours are audited. */
    @Test
    void theStateAndTheHoursAreAudited() {
        Work wp = flow.assignedWorkPack(Map.of("E1021", 40, "E1099", 60));
        Upload upload = replace(wp, "replace-13.xlsx", row(wp, "E1021", 50), row(wp, "E1150", 30));
        flow.settled(upload);
        flow.awaitReassignmentEnd(wp, END_REPLACED);

        assertThat(stateChanges(wp)).containsExactlyInAnyOrder("AwaitingFile->Assigning by production-team", "Assigning->Assigned by production-team",
                "Assigned->Replacing by production-team", "Replacing->Assigned by production-team");
        assertThat(jdbc.queryForList("""
                SELECT a.old_value || '->' || a.new_value || ' by ' || a.username FROM bpa.audit_log a JOIN bpa.worker_assignment w ON w.id = a.record_id
                JOIN bpa.worker k ON k.id = w.worker_id WHERE a.table_name = 'worker_assignment' AND a.column_name = 'estimated_hours'
                AND w.workpack_id = ? AND k.employee_ref = 'E1021' AND a.old_value IS NOT NULL""", String.class, wp.id())).containsExactly("40->50 by production-team");
        assertThat(jdbc.queryForList("""
                SELECT a.column_name || ' ' || a.old_value || '->' || coalesce(a.new_value, 'null') || ' by ' || a.username FROM bpa.audit_log a
                WHERE a.table_name = 'worker_assignment' AND a.new_value IS NULL
                  AND a.record_id IN (SELECT h.worker_assignment_id FROM bpa.worker_assignment_history h WHERE h.workpack_id = ? AND h.change_type = 'Removed')
                  AND a.column_name = 'estimated_hours'""", String.class, wp.id())).as("a removed assignment is audited with a null new value")
                .containsExactly("estimated_hours 60->null by production-team");
        assertThat(jdbc.queryForList("""
                SELECT a.column_name || '=' || a.new_value FROM bpa.audit_log a WHERE a.table_name = 'worker_assignment_history'
                  AND a.record_id IN (SELECT h.id FROM bpa.worker_assignment_history h WHERE h.workpack_id = ? AND h.assignment_file_id = ?::uuid AND h.change_type = 'Removed')
                  AND a.column_name IN ('change_type', 'old_estimated_hours', 'new_estimated_hours', 'source_file')""", String.class, wp.id(), upload.id()))
                .as("a history row is audited").containsExactlyInAnyOrder("change_type=Removed", "old_estimated_hours=60", "new_estimated_hours=0", "source_file=replace-13.xlsx");
        assertThat(jdbc.queryForList("""
                SELECT a.old_value || '->' || a.new_value || ' by ' || a.username FROM bpa.audit_log a
                WHERE a.table_name = 'hour_journal_line' AND a.column_name = 'status' AND a.old_value IS NOT NULL
                  AND a.record_id IN (SELECT l.id FROM bpa.hour_journal_line l JOIN bpa.hour_journal_header h ON h.id = l.header_id WHERE h.workpack_id = ?)""", String.class, wp.id()))
                .as("the Closed Line is audited").containsExactly("Open->Closed by production-team");
        assertThat(jdbc.queryForList("""
                SELECT a.old_value || '->' || a.new_value || ' by ' || a.username FROM bpa.audit_log a
                WHERE a.table_name = 'digital_timesheet' AND a.column_name = 'submission_status' AND a.old_value IS NOT NULL
                  AND a.record_id IN (SELECT t.id FROM bpa.digital_timesheet t WHERE t.workpack_id = ?)""", String.class, wp.id()))
                .as("the Cancelled time sheet is audited").containsExactly("Draft->Cancelled by production-team");
        assertThat(jdbc.queryForList("""
                SELECT a.column_name || '=' || a.new_value || ' by ' || a.username FROM bpa.audit_log a
                WHERE a.table_name = 'hour_journal_line' AND a.column_name = 'role'
                  AND a.record_id IN (SELECT l.id FROM bpa.hour_journal_line l JOIN bpa.hour_journal_header h ON h.id = l.header_id
                                      JOIN bpa.worker w ON w.id = l.worker_id WHERE h.workpack_id = ? AND w.employee_ref = 'E1150')""", String.class, wp.id()))
                .as("the new Line of E1150 is audited with the uploader as user (D63)").containsExactly("role=Rigger by production-team");
    }

    /** A job for a Work Pack that is not Replacing is refused with its state in the text and nothing is written (D61). */
    @Test
    void aJobForAWorkPackThatIsNotReplacingFailsWithNoRetries() {
        Work wp = flow.assignedWorkPack(Map.of("E1021", 40, "E1099", 60));
        Upload upload = replace(wp, "replace-14.xlsx", row(wp, "E1021", 50));
        flow.settled(upload);
        flow.awaitReassignmentEnd(wp, END_REPLACED);
        jdbc.update("UPDATE bpa.assignment_file_row SET outcome = 'Pending' WHERE assignment_file_id = ?::uuid", upload.id()); // by hand: rows still waiting, state Assigned
        JobClient client = Mockito.mock(JobClient.class, Mockito.RETURNS_DEEP_STUBS);

        worker.handle(client, job(93L, wp, upload));

        Mockito.verify(client.newFailCommand(93L)).retries(0);
        Mockito.verify(client.newFailCommand(93L).retries(0)).errorMessage(wp.number() + " is Assigned, not Replacing, so its replacement cannot be applied (D61)");
        Mockito.verify(client, Mockito.never()).newCompleteCommand(Mockito.anyLong());
        assertThat(assignments(wp)).as("the replacement had removed E1099 (no hours loaded); the refused job changes nothing more").containsExactly("E1021=50");
    }

    // --- helpers ---------------------------------------------------------------------------------------------------------------

    private static Object[] row(Work wp, String badge, Number hours) {
        return new Object[] {wp.number(), badge, hours};
    }

    private static byte[] file(Object[]... rows) {
        AssignmentFileBuilder builder = AssignmentFileBuilder.assignment();
        for (Object[] r : rows) {
            builder.row((String) r[0], (String) r[1], r[2]);
        }
        return builder.build();
    }

    private Upload replace(Work wp, String fileName, Object[]... rows) {
        Upload upload = flow.upload("production-team", fileName, file(rows));
        assertThat(upload.status()).as("upload: " + upload.body()).isEqualTo(201);
        return upload;
    }

    /** The Work Pack's assignments as "Badge No=hours" (trailing zeros stripped), by Badge No. */
    private List<String> assignments(Work wp) {
        return jdbc.query("""
                SELECT w.employee_ref, a.estimated_hours FROM bpa.worker_assignment a JOIN bpa.worker w ON w.id = a.worker_id
                WHERE a.workpack_id = ? ORDER BY w.employee_ref""",
                (rs, n) -> rs.getString(1) + "=" + rs.getBigDecimal(2).stripTrailingZeros().toPlainString(), wp.id());
    }

    private String flag(Work wp, String badge) {
        return jdbc.queryForObject("SELECT a.flag FROM bpa.worker_assignment a JOIN bpa.worker w ON w.id = a.worker_id WHERE a.workpack_id = ? AND w.employee_ref = ?",
                String.class, wp.id(), badge);
    }

    /** The history of one replacement file as "Badge No old->new Change source_file". */
    private List<String> history(Work wp, Upload upload) {
        return jdbc.query("""
                SELECT w.employee_ref, h.old_estimated_hours, h.new_estimated_hours, h.change_type, h.source_file FROM bpa.worker_assignment_history h
                JOIN bpa.worker w ON w.id = h.worker_id WHERE h.workpack_id = ? AND h.assignment_file_id = ?::uuid ORDER BY w.employee_ref""",
                (rs, n) -> rs.getString(1) + " " + rs.getBigDecimal(2).stripTrailingZeros().toPlainString() + "->"
                        + rs.getBigDecimal(3).stripTrailingZeros().toPlainString() + " " + rs.getString(4) + " " + rs.getString(5), wp.id(), upload.id());
    }

    private int historyCount(Work wp) {
        return jdbc.queryForObject("SELECT count(*) FROM bpa.worker_assignment_history WHERE workpack_id = ?", Integer.class, wp.id());
    }

    /** "Status hours" of the Worker's Journal Line on this Work Pack. */
    private String line(Work wp, String badge) {
        return jdbc.queryForObject("""
                SELECT l.status, l.estimated_hours FROM bpa.hour_journal_line l JOIN bpa.hour_journal_header h ON h.id = l.header_id
                JOIN bpa.worker w ON w.id = l.worker_id WHERE h.workpack_id = ? AND w.employee_ref = ?""",
                (rs, n) -> rs.getString(1) + " " + rs.getBigDecimal(2).stripTrailingZeros().toPlainString(), wp.id(), badge);
    }

    private String sheet(Work wp, String badge) {
        return jdbc.queryForObject("""
                SELECT t.submission_status FROM bpa.digital_timesheet t JOIN bpa.worker w ON w.id = t.worker_id WHERE t.workpack_id = ? AND w.employee_ref = ?""",
                String.class, wp.id(), badge);
    }

    private int lineCount(Work wp, String badge) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM bpa.hour_journal_line l JOIN bpa.hour_journal_header h ON h.id = l.header_id JOIN bpa.worker w ON w.id = l.worker_id
                WHERE h.workpack_id = ? AND w.employee_ref = ?""", Integer.class, wp.id(), badge);
    }

    private int sheetCount(Work wp, String badge) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM bpa.digital_timesheet t JOIN bpa.worker w ON w.id = t.worker_id WHERE t.workpack_id = ? AND w.employee_ref = ?""",
                Integer.class, wp.id(), badge);
    }

    /** Hours loaded (D36): a digital_timesheet_line of these hours on the Worker's time sheet for this Work Pack, inserted by hand since Epic 2 fills the table. */
    private void seedHoursLoaded(Work wp, String badge, String hours) {
        jdbc.update("""
                INSERT INTO bpa.digital_timesheet_line (digital_timesheet_id, work_date, phase, hours, created_by, updated_by)
                SELECT t.id, DATE '2026-11-04', 'Cutting', ?::numeric, 'test', 'test' FROM bpa.digital_timesheet t JOIN bpa.worker w ON w.id = t.worker_id
                WHERE t.workpack_id = ? AND w.employee_ref = ?""", hours, wp.id(), badge);
    }

    /** The audit rows of the flag on this Work Pack's assignments, as "old->new by user". */
    private List<String> flagAudit(Work wp) {
        return jdbc.queryForList("""
                SELECT coalesce(a.old_value, 'null') || '->' || a.new_value || ' by ' || a.username FROM bpa.audit_log a
                WHERE a.table_name = 'worker_assignment' AND a.column_name = 'flag'
                  AND a.record_id IN (SELECT id FROM bpa.worker_assignment WHERE workpack_id = ?)""", String.class, wp.id());
    }

    /** The Work Pack's assignment_state changes as "old->new by user" (the print's null -> AwaitingFile is not counted). */
    private List<String> stateChanges(Work wp) {
        return jdbc.queryForList("""
                SELECT old_value || '->' || new_value || ' by ' || username FROM bpa.audit_log
                WHERE table_name = 'workpack' AND record_id = ? AND column_name = 'assignment_state' AND old_value IS NOT NULL""", String.class, wp.id());
    }

    /** Audit rows of this Work Pack's own state, history, Lines and time sheets (not the whole log: other Work Packs write theirs meanwhile). */
    private int scopedAuditRows(Work wp) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM bpa.audit_log a WHERE (a.table_name = 'workpack' AND a.record_id = ?)
                   OR a.record_id IN (SELECT id FROM bpa.worker_assignment WHERE workpack_id = ?)
                   OR a.record_id IN (SELECT id FROM bpa.worker_assignment_history WHERE workpack_id = ?)
                   OR a.record_id IN (SELECT l.id FROM bpa.hour_journal_line l JOIN bpa.hour_journal_header h ON h.id = l.header_id WHERE h.workpack_id = ?)
                   OR a.record_id IN (SELECT id FROM bpa.digital_timesheet WHERE workpack_id = ?)""", Integer.class, wp.id(), wp.id(), wp.id(), wp.id(), wp.id());
    }

    private static ActivatedJob job(long key, Work wp, Upload upload) {
        ActivatedJob job = Mockito.mock(ActivatedJob.class);
        Mockito.when(job.getKey()).thenReturn(key);
        Mockito.when(job.getProcessInstanceKey()).thenReturn(4503599627370497L + key);
        Mockito.when(job.getElementId()).thenReturn(REPLACE_TASK);
        Mockito.when(job.getVariablesAsMap()).thenReturn(Map.of("workpackNumber", wp.number(), "assignmentFileId", upload.id()));
        return job;
    }
}
