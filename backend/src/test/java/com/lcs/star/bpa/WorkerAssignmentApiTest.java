package com.lcs.star.bpa;

import static com.lcs.star.bpa.AssignmentFlow.CATCH;
import static com.lcs.star.bpa.AssignmentFlow.activeIds;
import static com.lcs.star.bpa.CamundaSteps.completed;
import static com.lcs.star.bpa.CamundaSteps.elements;
import static com.lcs.star.bpa.CamundaSteps.poll;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.lcs.star.bpa.AssignmentFlow.Upload;
import com.lcs.star.bpa.AssignmentFlow.Work;
import com.lcs.star.bpa.worker.ValidateWorkerAssignmentWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import java.util.LinkedHashMap;
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
 * BPA-22: the row validation of {@code validate-worker-assignment} (ServiceTask_ValidateWorkerAssignment), through the real process.
 * One test per Gherkin scenario, named as in the Jira DoD. The Background: today is 30 Oct 2026 (Asia/Riyadh), Workers E1021 and E1099
 * exist, and the Work Pack is Released, scheduled and printed (assignment_state AwaitingFile) with its instance waiting at
 * IntermediateCatchEvent_AssignmentFileReceived. Work Packs are taken there the real way (AssignmentFlow), as in BPA-12; files come from
 * the AssignmentFileBuilder, never from templates/demo (09 S18, ADR 0009). The next steps, ServiceTask_CreateHourJournalLines (BPA-14) and ServiceTask_CreateDigitalTimesheets (BPA-15): an
 * instance that has Assigned Workers passes the first and stops at the second with an incident "built by BPA-15". "Assigned" means a worker_assignment row with those
 * hours and the result row reading "Assigned". A rejected-only file sends the instance back through Gateway_HasAssignedWorkers (No) to wait
 * at the catch event again (D5), so one Work Pack can take several files one after another.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(AssignmentFileApiTest.TestClockConfig.class)
class WorkerAssignmentApiTest {

    static final String VALIDATE = "ServiceTask_ValidateWorkerAssignment";
    static final String TIMESHEETS = "ServiceTask_CreateDigitalTimesheets";
    static final String GATEWAY = "Gateway_HasAssignedWorkers";
    static final String PENDING = "Pending";

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
    ValidateWorkerAssignmentWorker worker;

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
    void BPA22_workersAreAssignedFromFile() {
        Work wp = flow.awaiting();

        Upload upload = flow.upload("production-team", "assign-01.xlsx",
                AssignmentFileBuilder.assignment().row(wp.number(), "E1021", 40).row(wp.number(), "E1099", 60).build());

        assertThat(settled(upload)).containsExactly("Assigned", "Assigned");
        assertThat(assignments(wp)).containsExactly(Map.entry("E1021", "40"), Map.entry("E1099", "60"));
        assertThat(jdbc.queryForList("SELECT DISTINCT created_by || '/' || assignment_file_id::text FROM bpa.worker_assignment WHERE workpack_id = ?",
                String.class, wp.id())).as("created by the uploader (D63), from this file").containsExactly("production-team/" + upload.id());
        assertThat(flow.result(upload).path("rows")).as("the on-screen result (D49, D58)").extracting(r -> r.path("outcome").asText())
                .containsExactly("Assigned", "Assigned");
        atBpa15Incident(wp);
    }

    @Test
    void BPA22_agencyWorkerIsAssigned() {
        Work wp = flow.awaiting();

        Upload upload = flow.upload("production-team", "assign-agency.xlsx", AssignmentFileBuilder.assignment().row(wp.number(), "E2001", 30).build());

        assertThat(settled(upload)).containsExactly("Assigned");
        assertThat(assignments(wp)).containsExactly(Map.entry("E2001", "30"));
    }

    @Test
    void BPA22_allRowsRejectedKeepsWaiting() {
        Work wp = flow.awaiting();

        Upload upload = flow.upload("production-team", "assign-unknown.xlsx", AssignmentFileBuilder.assignment().row(wp.number(), "E9999", 40).build());

        assertThat(settled(upload)).containsExactly("Worker not in worker master data");
        assertThat(assignments(wp)).as("no Worker is assigned").isEmpty();
        backAtCatch(wp, 1);
        assertThat(flow.state(wp)).as("the state is unchanged").isEqualTo("AwaitingFile");
        assertThat(stateChanges(wp)).as("and nothing is audited (D64)").isEmpty();
        // the corrected file is routed to the waiting instance and assigns
        Upload corrected = flow.upload("production-team", "assign-corrected.xlsx", AssignmentFileBuilder.assignment().row(wp.number(), "E1021", 40).build());
        assertThat(settled(corrected)).containsExactly("Assigned");
        assertThat(assignments(wp)).containsExactly(Map.entry("E1021", "40"));
    }

    @Test
    void BPA22_workerNotInMasterDataIsRejected() {
        Work wp = flow.awaiting();

        Upload upload = flow.upload("production-team", "assign-mixed.xlsx",
                AssignmentFileBuilder.assignment().row(wp.number(), "E1021", 40).row(wp.number(), "E9999", 20).build());

        assertThat(settled(upload)).containsExactly("Assigned", "Worker not in worker master data");
        assertThat(assignments(wp)).containsExactly(Map.entry("E1021", "40"));
    }

    @Test
    void BPA22_duplicateWorkerRowsAreRejected() {
        Work wp = flow.awaiting();

        Upload upload = flow.upload("production-team", "assign-twice.xlsx",
                AssignmentFileBuilder.assignment().row(wp.number(), "E1021", 40).row(wp.number(), "E1021", 20).build());

        assertThat(settled(upload)).containsExactly("Worker listed twice for this Work Pack", "Worker listed twice for this Work Pack");
        assertThat(assignments(wp)).as("E1021 is not assigned").isEmpty();
    }

    @Test
    void BPA22_sameWorkerOnTwoWorkPacksIsValid() {
        Work a = flow.awaiting();
        Work b = flow.awaiting();

        Upload upload = flow.upload("production-team", "assign-two-workpacks.xlsx",
                AssignmentFileBuilder.assignment().row(a.number(), "E1021", 40).row(b.number(), "E1021", 20).build());

        assertThat(settled(upload)).containsExactly("Assigned", "Assigned");
        assertThat(assignments(a)).containsExactly(Map.entry("E1021", "40"));
        assertThat(assignments(b)).containsExactly(Map.entry("E1021", "20"));
    }

    /** The outline's seven rows. The five rejected ones go to one Work Pack, which waits again after each (D5); the two accepted ones to two Work Packs. */
    @Test
    void BPA22_estimatedHoursValidation() {
        Work wp = flow.awaiting();
        Map<String, Object> rejected = new LinkedHashMap<>();
        rejected.put("Estimated hours is required", null);
        rejected.put("Estimated hours must be a number", "abc");
        rejected.put("Estimated hours must be greater than 0", 0);
        rejected.put("Estimated hours can have at most 2 decimal places", 0.001);
        rejected.put("Estimated hours cannot exceed 99,999.99", 100000);

        int round = 0;
        for (Map.Entry<String, Object> example : rejected.entrySet()) {
            Upload upload = flow.upload("production-team", "assign-hours-" + (++round) + ".xlsx",
                    AssignmentFileBuilder.assignment().cells(wp.number(), "E1021", example.getValue(), null, null).build());
            assertThat(settled(upload)).as("hours " + example.getValue()).containsExactly(example.getKey());
            assertThat(assignments(wp)).isEmpty();
            backAtCatch(wp, round);
        }
        Upload smallest = flow.upload("production-team", "assign-hours-0.01.xlsx", AssignmentFileBuilder.assignment().row(wp.number(), "E1021", 0.01).build());
        assertThat(settled(smallest)).containsExactly("Assigned");
        assertThat(assignments(wp)).containsExactly(Map.entry("E1021", "0.01"));

        Work other = flow.awaiting();
        Upload largest = flow.upload("production-team", "assign-hours-max.xlsx", AssignmentFileBuilder.assignment().row(other.number(), "E1021", 99999.99).build());
        assertThat(settled(largest)).containsExactly("Assigned");
        assertThat(assignments(other)).containsExactly(Map.entry("E1021", "99999.99"));
    }

    @Test
    void BPA22_workpackNumberRequired() {
        Work wp = flow.awaiting();

        Upload upload = flow.upload("production-team", "assign-no-number.xlsx",
                AssignmentFileBuilder.assignment().row(wp.number(), "E1021", 40).cells(null, "E1099", 20, null, null).build());

        assertThat(settled(upload)).containsExactly("Assigned", "Workpack number is required");
        assertThat(assignments(wp)).containsExactly(Map.entry("E1021", "40"));
    }

    @Test
    void BPA22_badgeNoRequired() {
        Work wp = flow.awaiting();

        Upload upload = flow.upload("production-team", "assign-no-badge.xlsx",
                AssignmentFileBuilder.assignment().row(wp.number(), "E1021", 40).cells(wp.number(), null, 20, null, null).build());

        assertThat(settled(upload)).containsExactly("Assigned", "Badge No is required");
        assertThat(assignments(wp)).containsExactly(Map.entry("E1021", "40"));
    }

    @Test
    void BPA22_employeeNameIsIgnored() {
        Work wp = flow.awaiting();

        Upload upload = flow.upload("production-team", "assign-name.xlsx",
                AssignmentFileBuilder.assignment().cells(wp.number(), "E1021", 40, "Different Name", null).build());

        assertThat(settled(upload)).containsExactly("Assigned");
        assertThat(assignments(wp)).containsExactly(Map.entry("E1021", "40"));
        assertThat(flow.result(upload).path("rows").get(0).path("workerName").asText()).as("the master name is shown").isEqualTo("Worker E1021");
    }

    @Test
    void BPA22_remarksLength() {
        Work wp = flow.awaiting();

        Upload tooLong = flow.upload("production-team", "assign-remarks-251.xlsx",
                AssignmentFileBuilder.assignment().cells(wp.number(), "E1021", 40, null, "x".repeat(251)).build());
        assertThat(settled(tooLong)).containsExactly("Remarks can be at most 250 characters");
        assertThat(assignments(wp)).isEmpty();
        backAtCatch(wp, 1);

        String exactly250 = "y".repeat(250);
        Upload ok = flow.upload("production-team", "assign-remarks-250.xlsx",
                AssignmentFileBuilder.assignment().cells(wp.number(), "E1021", 40, null, exactly250).build());
        assertThat(settled(ok)).containsExactly("Assigned");
        assertThat(jdbc.queryForObject("SELECT remarks FROM bpa.worker_assignment WHERE workpack_id = ?", String.class, wp.id())).isEqualTo(exactly250);
    }

    @Test
    void BPA22_acceptedRowSetsAssigning() {
        Work wp = flow.awaiting();

        Upload upload = flow.upload("production-team", "assign-assigning.xlsx",
                AssignmentFileBuilder.assignment().row(wp.number(), "E1021", 40).row(wp.number(), "E9999", 20).build());

        assertThat(settled(upload)).containsExactly("Assigned", "Worker not in worker master data");
        assertThat(flow.state(wp)).isEqualTo("Assigning");
        assertThat(jdbc.queryForList("""
                SELECT old_value || '->' || new_value || ' by ' || username FROM bpa.audit_log
                WHERE table_name = 'workpack' AND record_id = ? AND column_name = 'assignment_state' AND old_value IS NOT NULL
                """, String.class, wp.id())).as("D64: one audit row for the state change").containsExactly("AwaitingFile->Assigning by production-team");
    }

    @Test
    void BPA22_allRejectedSetsAwaitingFile() {
        Work wp = flow.awaiting();

        byte[] rejectedFile = AssignmentFileBuilder.assignment().row(wp.number(), "E9999", 40).build();
        Upload upload = flow.upload("production-team", "assign-all-rejected.xlsx", rejectedFile);

        assertThat(settled(upload)).containsExactly("Worker not in worker master data");
        assertThat(flow.state(wp)).isEqualTo("AwaitingFile");
        backAtCatch(wp, 1);
        assertThat(stateChanges(wp)).as("nothing changed, so nothing is audited (D64)").isEmpty();
        // the same file again is the duplicate of D73 (plan Q3): it was published, so it is not routed; the user changes the file and resends
        Upload same = flow.upload("production-team", "assign-all-rejected-again.xlsx", rejectedFile);
        assertThat(same.body().path("outcome").asText()).isEqualTo("File already processed");
        assertThat(flow.state(wp)).isEqualTo("AwaitingFile");
        byte[] corrected = AssignmentFileBuilder.assignment().row(wp.number(), "E1021", 40).build();
        Upload resend = flow.upload("production-team", "assign-corrected.xlsx", corrected);
        assertThat(resend.status()).isEqualTo(201);
        assertThat(flow.claims(AssignmentFlow.sha256(corrected), wp.number())).as("the corrected file is routed to its process (BPA-12)").isEqualTo(1);
        assertThat(settled(resend)).containsExactly("Assigned");
    }

    // --- not scenarios ---------------------------------------------------------------------------------------------------------

    /** Every business column of a new assignment is audited, by the uploader, once (D64, data model 6.1). */
    @Test
    void anAssignmentIsAudited() {
        Work wp = flow.awaiting();
        Upload upload = flow.upload("production-team", "assign-audit.xlsx",
                AssignmentFileBuilder.assignment().cells(wp.number(), "E1021", 40, null, "night shift").build());
        assertThat(settled(upload)).containsExactly("Assigned");

        List<String> audit = jdbc.queryForList("""
                SELECT a.column_name || '=' || coalesce(a.new_value, 'null') || ' (old ' || coalesce(a.old_value, 'null') || ') by ' || a.username
                FROM bpa.audit_log a JOIN bpa.worker_assignment w ON w.id = a.record_id
                WHERE a.table_name = 'worker_assignment' AND w.workpack_id = ? ORDER BY a.column_name
                """, String.class, wp.id());

        assertThat(audit).contains("estimated_hours=40 (old null) by production-team", "remarks=night shift (old null) by production-team",
                "assignment_file_id=" + upload.id() + " (old null) by production-team", "workpack_id=" + wp.id() + " (old null) by production-team")
                .hasSize(5);
    }

    /** The job retried after it had finished (a lost reply): one set of assignments, the same count, no second audit row (D65, 09 S7). */
    @Test
    void aRetriedJobChangesNothingAndAnswersTheSameCount() {
        Work wp = flow.awaiting();
        Upload upload = flow.upload("production-team", "assign-retry.xlsx",
                AssignmentFileBuilder.assignment().row(wp.number(), "E1021", 40).row(wp.number(), "E9999", 20).build());
        assertThat(settled(upload)).containsExactly("Assigned", "Worker not in worker master data");
        int audit = auditRows();
        JobClient client = Mockito.mock(JobClient.class, Mockito.RETURNS_DEEP_STUBS);

        worker.handle(client, job(51L, wp.instance(), upload.id()));

        Mockito.verify(client.newCompleteCommand(51L)).variables(Map.of("assignedWorkerCount", 1));
        assertThat(assignments(wp)).containsExactly(Map.entry("E1021", "40"));
        assertThat(auditRows()).as("the retry adds no audit row").isEqualTo(audit);
        assertThat(flow.state(wp)).isEqualTo("Assigning");
    }

    /** Rows still Pending on a Work Pack that is no longer AwaitingFile are not validated: an incident that says why, nothing saved (D61). */
    @Test
    void aJobForAWorkPackThatIsNotAwaitingFileFailsWithNoRetries() {
        Work wp = flow.awaiting();
        Upload upload = flow.upload("production-team", "assign-guard.xlsx", AssignmentFileBuilder.assignment().row(wp.number(), "E1021", 40).build());
        assertThat(settled(upload)).containsExactly("Assigned");
        // by hand, since no process path reaches this: the Work Pack is Assigning and one row is Pending again
        jdbc.update("UPDATE bpa.assignment_file_row SET outcome = 'Pending' WHERE assignment_file_id = ?::uuid", upload.id());
        jdbc.update("DELETE FROM bpa.worker_assignment WHERE workpack_id = ?", wp.id());
        JobClient client = Mockito.mock(JobClient.class, Mockito.RETURNS_DEEP_STUBS);

        worker.handle(client, job(52L, wp.instance(), upload.id()));

        Mockito.verify(client.newFailCommand(52L)).retries(0);
        Mockito.verify(client.newFailCommand(52L).retries(0)).errorMessage(wp.number() + " is Assigning, not AwaitingFile, so its assignment rows cannot be validated (D61)");
        assertThat(assignments(wp)).as("nothing saved").isEmpty();
        assertThat(rowOutcomes(upload)).containsExactly(PENDING);
    }

    // --- helpers ---------------------------------------------------------------------------------------------------------------

    /** Waits until no row of the upload reads Pending (the worker has answered) and returns the outcomes in file order, as the result screen shows them. */
    private List<String> settled(Upload upload) {
        assertThat(upload.status()).as("upload: " + upload.body()).isEqualTo(201);
        return poll(() -> rowOutcomes(upload), outcomes -> !outcomes.isEmpty() && !outcomes.contains(PENDING), "the rows of " + upload.id() + " answered");
    }

    private List<String> rowOutcomes(Upload upload) {
        return elements(flow.result(upload).path("rows")).stream().map(r -> r.path("outcome").asText()).toList();
    }

    /** The Work Pack's assignments as Badge No to hours text (trailing zeros stripped), by Badge No. */
    private List<Map.Entry<String, String>> assignments(Work wp) {
        return jdbc.query("""
                SELECT w.employee_ref, a.estimated_hours FROM bpa.worker_assignment a JOIN bpa.worker w ON w.id = a.worker_id
                WHERE a.workpack_id = ? ORDER BY w.employee_ref
                """, (rs, n) -> Map.entry(rs.getString(1), rs.getBigDecimal(2).stripTrailingZeros().toPlainString()), wp.id());
    }

    /** Gateway_HasAssignedWorkers has answered No this many times and the instance waits at the catch event again (D5). */
    private void backAtCatch(Work wp, int times) {
        poll(() -> completed(wp.instance(), GATEWAY, times) && activeIds(wp).contains(CATCH), done -> done,
                "the instance of " + wp.number() + " back at the catch event after " + times + " rejected file(s)");
    }

    /** The instance has Assigned Workers and its Lines (BPA-14), so it stops at BPA-15's step with its incident. */
    private void atBpa15Incident(Work wp) {
        poll(() -> activeIds(wp), ids -> ids.contains(TIMESHEETS), "the instance of " + wp.number() + " at " + TIMESHEETS);
        List<JsonNode> incidents = poll(() -> elements(IntegrationApp.admin.post("/v2/incidents/search",
                        Map.of("filter", Map.of("processInstanceKey", Long.toString(wp.instance())))).body().path("items")),
                list -> !list.isEmpty(), "an incident on the instance of " + wp.number());
        assertThat(incidents.get(0).path("errorMessage").asText()).contains("built by BPA-15");
    }

    /** The Work Pack's assignment_state changes made by BPA-22 (the first audit row, null to AwaitingFile, is the print's). */
    private List<String> stateChanges(Work wp) {
        return jdbc.queryForList("SELECT old_value || '->' || new_value FROM bpa.audit_log WHERE table_name = 'workpack' AND record_id = ? AND column_name = 'assignment_state' AND old_value IS NOT NULL",
                String.class, wp.id());
    }

    private int auditRows() {
        return jdbc.queryForObject("SELECT count(*) FROM bpa.audit_log", Integer.class);
    }

    private static ActivatedJob job(long key, long instance, String assignmentFileId) {
        ActivatedJob job = Mockito.mock(ActivatedJob.class);
        Mockito.when(job.getKey()).thenReturn(key);
        Mockito.when(job.getProcessInstanceKey()).thenReturn(instance);
        Mockito.when(job.getElementId()).thenReturn(VALIDATE);
        Mockito.when(job.getVariablesAsMap()).thenReturn(Map.of("assignmentFileId", assignmentFileId));
        return job;
    }
}
