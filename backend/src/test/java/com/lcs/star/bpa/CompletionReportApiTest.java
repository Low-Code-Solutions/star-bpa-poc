package com.lcs.star.bpa;

import static com.lcs.star.bpa.CamundaSteps.json;
import static com.lcs.star.bpa.CamundaSteps.openTasks;
import static com.lcs.star.bpa.CamundaSteps.poll;
import static com.lcs.star.bpa.CamundaSteps.toJson;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.lcs.star.bpa.CamundaRest.Reply;
import com.lcs.star.bpa.progress.CompletionProgressService;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BPA-59 (E2-09): PPC uploads the weekly FMS completion report. One test per Gherkin scenario, named as in the Jira Definition of Done (the two Scenario
 * Outlines are one test each). Background: today is 20 Nov 2026 (Asia/Riyadh); the weekly instance for the week ending 19 Nov 2026 waits at
 * UserTask_UploadCompletionReport (started there with startInstructions, with a progress_period row that carries its key); {@link BookHoursFixture}
 * with {@code completionBackground} (WP-000457 and WP-000461 In Progress, WP-000480 Released); logged in as ppc-team. The upload names the open task
 * ({@code taskKey}, plan Q8). Every file comes from {@link CompletionReportBuilder}, in memory (no .xlsx is committed under backend/, the BPA-43 guard); the demo files in
 * templates/demo are never used. The progress source is set through the environment ({@code bpa.progress.source}), which the app reads at each call.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(AssignmentFileApiTest.TestClockConfig.class)
class CompletionReportApiTest {

    static final Instant TODAY = Instant.parse("2026-11-20T07:00:00Z"); // 10:00 in Riyadh
    static final String PROCESS = "Process_PPC_WeeklyProgressPeriod";
    static final String UPLOAD = "UserTask_UploadCompletionReport";
    static final String APPROVE = "UserTask_ApproveCompletion";
    static final LocalDate W19 = LocalDate.of(2026, 11, 19);
    static final String READ = "Read";
    static final String NO_USABLE = "No usable rows in the completion report";

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
    ConfigurableEnvironment environment;

    @Autowired
    CompletionProgressService progress;

    private AssignmentFlow flow;
    private long instance;
    private String task;

    /** All test classes share one Camunda: no weekly instance left waiting by another class may take this class's tasks. */
    @BeforeAll
    static void noInstanceLeftWaiting() {
        IntegrationApp.background();
        cancelWaiting();
    }

    private static void cancelWaiting() {
        for (String element : new String[] {"ServiceTask_OpenProgressPeriod", "ServiceTask_GenerateFmsHoursFile", "UserTask_SendHoursToFms",
                "ServiceTask_RecordFmsHandOff", UPLOAD, "ServiceTask_ReadCompletionReport", APPROVE}) {
            CamundaSteps.cancelInstancesWaitingAt(element);
        }
    }

    @BeforeEach
    void background() {
        cancelWaiting();
        clock.set(TODAY);
        BookHoursFixture fixture = new BookHoursFixture(jdbc);
        fixture.reset();
        fixture.completionBackground();
        flow = new AssignmentFlow(port, jdbc);
        instance = startAtUpload();
        insertPeriod(W19, instance);
        task = awaitTask(instance, UPLOAD, null);
        progressSource("FMS");
    }

    @AfterEach
    void removeSource() {
        environment.getPropertySources().remove("bpa59");
    }

    // --- the scenarios ----------------------------------------------------------------------------------------------------

    @Test
    void BPA59_validReportIsRead() {
        HttpResponse<String> uploaded = upload("ppc-team", task, "fms-completion-wk47.xlsx", CompletionReportBuilder.wk47());

        assertThat(uploaded.statusCode()).as(uploaded.body()).isEqualTo(201);
        assertThat(outcomes(uploaded)).containsExactly(READ, READ);
        HttpResponse<String> done = completeWith(task, reportId(uploaded));
        assertThat(done.statusCode()).as(done.body()).isEqualTo(204);
        awaitTask(instance, APPROVE, null);
        assertThat(reported("WP-000457")).isEqualByComparingTo("40");
        assertThat(reported("WP-000461")).isEqualByComparingTo("100");
    }

    @Test
    void BPA59_unknownWorkPackRow() {
        HttpResponse<String> uploaded = upload("ppc-team", task, "r.xlsx", CompletionReportBuilder.report().row("WP-999999", 40, 10).xlsx());

        assertThat(outcomes(uploaded)).containsExactly("Work Pack not found");
    }

    @Test
    void BPA59_workPackNotInProgressRow() {
        HttpResponse<String> uploaded = upload("ppc-team", task, "r.xlsx", CompletionReportBuilder.report().row("WP-000480", 10, null).xlsx());

        assertThat(outcomes(uploaded)).containsExactly("Work Pack is not In Progress");
    }

    @Test
    void BPA59_completionNotANumberRow() {
        HttpResponse<String> uploaded = upload("ppc-team", task, "r.xlsx", CompletionReportBuilder.report().row("WP-000457", "forty", 150).xlsx());

        assertThat(outcomes(uploaded)).containsExactly("Completion % must be a number");
    }

    @Test
    void BPA59_completionThreeDecimalsRow() {
        HttpResponse<String> uploaded = upload("ppc-team", task, "r.xlsx", CompletionReportBuilder.report().row("WP-000457", 40.125, 150).xlsx());

        assertThat(outcomes(uploaded)).containsExactly("Completion % can have at most 2 decimal places");
    }

    @Test
    void BPA59_over100IsReadForApproval() {
        HttpResponse<String> uploaded = upload("ppc-team", task, "r.xlsx", CompletionReportBuilder.report().row("WP-000457", 120, 150).xlsx());

        assertThat(outcomes(uploaded)).containsExactly(READ);
    }

    @Test
    void BPA59_reportWithNoUsableRowGoesBack() {
        HttpResponse<String> uploaded = upload("ppc-team", task, "r.xlsx", CompletionReportBuilder.report().row("WP-999999", 40, 10).xlsx());
        UUID report = reportId(uploaded);

        HttpResponse<String> done = completeWith(task, report);

        assertThat(done.statusCode()).as(done.body()).isEqualTo(204);
        String back = awaitTask(instance, UPLOAD, task);
        JsonNode again = json(flow.loggedInAs("ppc-team").get("/api/v1/tasks/" + back).body());
        List<String> messages = new ArrayList<>();
        again.path("validationMessages").forEach(m -> messages.add(m.asText()));
        assertThat(messages).contains(NO_USABLE);
        assertThat(jdbc.queryForObject("SELECT outcome FROM bpa.completion_report WHERE id = ?", String.class, report)).isEqualTo("Invalid");
    }

    @Test
    void BPA59_latestUploadIsUsed() {
        HttpResponse<String> first = upload("ppc-team", task, "first.xlsx", CompletionReportBuilder.report().row("WP-000457", 40, 150).xlsx());
        HttpResponse<String> second = upload("ppc-team", task, "second.xlsx", CompletionReportBuilder.report().row("WP-000457", 45, 150).xlsx());
        assertThat(first.statusCode()).isEqualTo(201);

        HttpResponse<String> done = completeWith(task, reportId(second));

        assertThat(done.statusCode()).as(done.body()).isEqualTo(204);
        awaitTask(instance, APPROVE, null);
        assertThat(reported("WP-000457")).isEqualByComparingTo("45");
    }

    @Test
    void BPA59_otherPersonasCannotUploadReport() {
        HttpResponse<String> refused = upload("supervisor", task, "fms-completion-wk47.xlsx", CompletionReportBuilder.wk47());

        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.completion_report", Integer.class)).isZero();
    }

    @Test
    void BPA59_duplicateWorkPackRowIsRejected() {
        HttpResponse<String> uploaded = upload("ppc-team", task, "r.xlsx",
                CompletionReportBuilder.report().row("WP-000457", 40, 150).row("WP-000457", 45, 150).xlsx());

        List<JsonNode> rows = rows(uploaded);
        assertThat(rows.get(0).path("outcome").asText()).isEqualTo(READ);
        assertThat(rows.get(0).path("completionPct").asText()).isEqualTo("40");
        assertThat(rows.get(1).path("outcome").asText()).isEqualTo("Duplicate row for WP-000457");
    }

    /** The Scenario Outline: "notes.txt" renamed to "r.xlsx" is a file-level outcome; a 6 MB file is refused with 413. */
    @Test
    void BPA59_unreadableOrLargeFileIsRefused() {
        HttpResponse<String> unreadable = upload("ppc-team", task, "r.xlsx", CompletionReportBuilder.notASpreadsheet());
        HttpResponse<String> large = upload("ppc-team", task, "big.xlsx", new byte[6 * 1024 * 1024]);

        assertThat(unreadable.statusCode()).as(unreadable.body()).isEqualTo(201);
        assertThat(json(unreadable.body()).path("outcome").asText()).isEqualTo("File cannot be read");
        assertThat(rows(unreadable)).isEmpty();
        assertThat(large.statusCode()).isEqualTo(413);
        assertThat(json(large.body()).path("detail").asText()).isEqualTo("File is larger than 5 MB");
    }

    /** The Scenario Outline, its three examples in one test: the setting progress.source and the rows' Source. */
    @Test
    void BPA59_theConfiguredProgressSourceIsAccepted() {
        progressSource("FMS");
        assertThat(outcomes(upload("ppc-team", task, "r.xlsx", CompletionReportBuilder.report().row("WP-000457", 40, 150, "FMS").xlsx()))).containsExactly(READ);

        progressSource("PMS");
        assertThat(outcomes(upload("ppc-team", task, "r.xlsx", CompletionReportBuilder.report().row("WP-000457", 40, 150, "PMS").xlsx()))).containsExactly(READ);

        progressSource("FMS");
        assertThat(outcomes(upload("ppc-team", task, "r.xlsx", CompletionReportBuilder.report().row("WP-000457", 40, 150, "PMS").xlsx())))
                .containsExactly("Source PMS is not the configured progress source");
    }

    // --- extras (not Gherkin scenarios) ---------------------------------------------------------------------------------------

    @Test
    void aReportForAnotherWeekIsRefusedAtCompletion() {
        long other = startAtUpload();
        insertPeriod(LocalDate.of(2026, 11, 26), other);
        String otherTask = awaitTask(other, UPLOAD, null);
        UUID foreign = reportId(upload("ppc-team", otherTask, "r.xlsx", CompletionReportBuilder.report().row("WP-000457", 40, 150).xlsx()));

        HttpResponse<String> refused = completeWith(task, foreign);

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(json(refused.body()).path("detail").asText()).isEqualTo("Completion report is not for this week");
        assertThat(openTasks(instance, UPLOAD)).as("the task stays open").hasSize(1);
    }

    @Test
    void completingWithoutAReportIsRefused() {
        HttpResponse<String> refused = flow.loggedInAs("ppc-team").post("/api/v1/tasks/" + task + "/complete", toJson(Map.of("variables", Map.of())));

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(json(refused.body()).path("detail").asText()).isEqualTo("Completion report is required");
    }

    @Test
    void anUnknownSourceIsLoggedAsWritten() {
        HttpResponse<String> uploaded = upload("ppc-team", task, "r.xlsx", CompletionReportBuilder.report().row("WP-000457", 40, 150, "XYZ").xlsx());

        assertThat(outcomes(uploaded)).containsExactly("Source XYZ is not the configured progress source");
        Map<String, Object> row = jdbc.queryForMap("SELECT source, source_raw FROM bpa.completion_report_row");
        assertThat(row.get("source_raw")).isEqualTo("XYZ");
        assertThat(row.get("source")).as("source holds only FMS or PMS").isNull();
    }

    @Test
    void aBlankSourceIsRefusedAndLogged() {
        HttpResponse<String> uploaded = upload("ppc-team", task, "r.xlsx", CompletionReportBuilder.report().row("WP-000457", 40, 150, null).xlsx());

        assertThat(outcomes(uploaded)).containsExactly("Source is required");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.completion_report_row", Integer.class)).isEqualTo(1);
    }

    @Test
    void theColumnsAreFoundByNameInAnyOrderAndCase() {
        byte[] file = CompletionReportBuilder.report().headers("SOURCE", "workpack NUMBER", "Completion %").rowAs("FMS", "WP-000457", 40).xlsx();

        HttpResponse<String> uploaded = upload("ppc-team", task, "r.xlsx", file);

        assertThat(outcomes(uploaded)).containsExactly(READ);
    }

    @Test
    void aCsvIsReadToo() {
        HttpResponse<String> uploaded = upload("ppc-team", task, "r.csv", CompletionReportBuilder.report().row("WP-000457", 40, 150).csv());

        assertThat(outcomes(uploaded)).containsExactly(READ);
    }

    @Test
    void theResultCanBeReadAgain() {
        HttpResponse<String> uploaded = upload("ppc-team", task, "r.xlsx", CompletionReportBuilder.report().row("WP-000457", 40, 150).xlsx());

        HttpResponse<String> again = flow.loggedInAs("ppc-team").get("/api/v1/completion-reports/" + reportId(uploaded) + "/result");

        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(json(again.body())).isEqualTo(json(uploaded.body()));
        assertThat(flow.loggedInAs("supervisor").get("/api/v1/completion-reports/" + reportId(uploaded) + "/result").statusCode()).isEqualTo(403);
    }

    @Test
    void anAcceptedReportClearsWorkPacksMissingFromIt() {
        UUID period = jdbc.queryForObject("SELECT id FROM bpa.progress_period WHERE process_instance_key = ?", UUID.class, instance);
        jdbc.update("""
                INSERT INTO bpa.workpack_progress (workpack_id, progress_period_id, reported_pct, created_by, updated_by)
                SELECT id, ?, 70, 'test', 'test' FROM bpa.workpack WHERE workpack_number = 'WP-000461'""", period);
        UUID report = reportId(upload("ppc-team", task, "r.xlsx", CompletionReportBuilder.report().row("WP-000457", 40, 150).xlsx()));

        assertThat(completeWith(task, report).statusCode()).isEqualTo(204);

        awaitTask(instance, APPROVE, null);
        assertThat(reported("WP-000457")).isEqualByComparingTo("40");
        assertThat(jdbc.queryForObject("""
                SELECT reported_pct FROM bpa.workpack_progress p JOIN bpa.workpack w ON w.id = p.workpack_id WHERE w.workpack_number = 'WP-000461'""",
                BigDecimal.class)).as("a Work Pack missing from the accepted report is cleared").isNull();
    }

    /** Re-run key (ADR 0007, D65): a retried read step finds the report decided and writes nothing more; a first decision is audited once per column. */
    @Test
    void aRerunOfReadCompletionReportChangesNothing() {
        UUID period = jdbc.queryForObject("SELECT id FROM bpa.progress_period WHERE process_instance_key = ?", UUID.class, instance);
        UUID report = reportId(upload("ppc-team", task, "r.xlsx", CompletionReportBuilder.report().row("WP-000457", 40, 150).xlsx()));

        CompletionProgressService.Decision first = progress.decide(report, period, "ppc-team");
        jdbc.update("UPDATE bpa.workpack_progress SET reported_pct = 99 WHERE reported_pct = 40"); // a change a second run must not undo
        CompletionProgressService.Decision again = progress.decide(report, period, "ppc-team");

        assertThat(first).isEqualTo(CompletionProgressService.Decision.ACCEPTED);
        assertThat(again).isEqualTo(CompletionProgressService.Decision.ACCEPTED);
        assertThat(reported("WP-000457")).as("the second run applied nothing").isEqualByComparingTo("99");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.workpack_progress", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.audit_log WHERE table_name = 'workpack_progress' AND column_name = 'reported_pct'", Integer.class))
                .as("audited once").isEqualTo(1);
    }

    // --- helpers ----------------------------------------------------------------------------------------------------------------

    private long startAtUpload() {
        Reply started = IntegrationApp.admin.post("/v2/process-instances", Map.of(
                "processDefinitionId", PROCESS, "startInstructions", List.of(Map.of("elementId", UPLOAD))));
        assertThat(started.status()).as("start at " + UPLOAD + ": " + started.body()).isEqualTo(200);
        return Long.parseLong(started.body().path("processInstanceKey").asText());
    }

    /** The progress period the weekly instance belongs to: the row BPA-58's open-progress-period would have written. */
    private void insertPeriod(LocalDate weekEnding, long processInstanceKey) {
        jdbc.update("""
                INSERT INTO bpa.progress_period (week_ending, cut_off_at, status, process_instance_key, created_by, updated_by)
                VALUES (?, ?, 'CutOff', ?, 'test', 'test')""", java.sql.Date.valueOf(weekEnding),
                java.sql.Timestamp.from(weekEnding.atTime(20, 59).toInstant(java.time.ZoneOffset.UTC)), processInstanceKey);
    }

    /** The key of an open task of this element, other than {@code except} when given (the task the instance came back to). */
    private String awaitTask(long forInstance, String elementId, String except) {
        return poll(() -> openTasks(forInstance, elementId).stream().map(t -> t.path("userTaskKey").asText()).filter(k -> !k.equals(except)).findFirst(),
                found -> found.isPresent(), elementId + " of " + forInstance).orElseThrow();
    }

    private HttpResponse<String> upload(String user, String taskKey, String fileName, byte[] file) {
        return flow.loggedInAs(user).postMultipart("/api/v1/completion-reports", Map.of("taskKey", taskKey), fileName, file);
    }

    private HttpResponse<String> completeWith(String taskKey, UUID report) {
        return flow.loggedInAs("ppc-team").post("/api/v1/tasks/" + taskKey + "/complete",
                toJson(Map.of("variables", Map.of("completionReportId", report.toString()))));
    }

    private static UUID reportId(HttpResponse<String> uploaded) {
        return UUID.fromString(json(uploaded.body()).path("id").asText());
    }

    private static List<JsonNode> rows(HttpResponse<String> uploaded) {
        List<JsonNode> rows = new ArrayList<>();
        json(uploaded.body()).path("rows").forEach(rows::add);
        return rows;
    }

    private static List<String> outcomes(HttpResponse<String> uploaded) {
        assertThat(uploaded.statusCode()).as(uploaded.body()).isEqualTo(201);
        return rows(uploaded).stream().map(r -> r.path("outcome").asText()).toList();
    }

    private BigDecimal reported(String workpackNumber) {
        return jdbc.queryForObject("""
                SELECT p.reported_pct FROM bpa.workpack_progress p JOIN bpa.workpack w ON w.id = p.workpack_id JOIN bpa.progress_period d ON d.id = p.progress_period_id
                WHERE w.workpack_number = ? AND d.week_ending = ?""", BigDecimal.class, workpackNumber, java.sql.Date.valueOf(W19));
    }

    private void progressSource(String source) {
        environment.getPropertySources().remove("bpa59");
        environment.getPropertySources().addFirst(new MapPropertySource("bpa59", Map.of("bpa.progress.source", source)));
    }
}
