package com.lcs.star.bpa;

import static com.lcs.star.bpa.CamundaSteps.json;
import static com.lcs.star.bpa.CamundaSteps.openTasks;
import static com.lcs.star.bpa.CamundaSteps.poll;
import static com.lcs.star.bpa.CamundaSteps.toJson;
import static org.assertj.core.api.Assertions.assertThat;

import com.lcs.star.bpa.BookHoursFixture.FmsIds;
import com.lcs.star.bpa.CamundaRest.Reply;
import com.lcs.star.bpa.fms.FmsHoursExportService;
import com.lcs.star.bpa.persistence.ProgressPeriodRepository;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
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
 * BPA-58 (E2-08): PPC downloads each week's approved hours as an FMS timesheet file. One test per Gherkin scenario, named as in the Jira Definition of Done.
 * Background: today is 20 Nov 2026 (Asia/Riyadh); {@link BookHoursFixture} (WP-000457, WP-000470 through {@code fmsBackground}) with the five lines of the story.
 * "The cut-off for week ending X fires" is the test setting the clock to X at 23:59:30 Riyadh and starting Process_PPC_WeeklyProgressPeriod at
 * ServiceTask_OpenProgressPeriod; the cron timer is never waited for. The workers run in the app. A file is read back through the API as ppc-team.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(AssignmentFileApiTest.TestClockConfig.class)
class FmsHoursExportApiTest {

    static final Instant TODAY = Instant.parse("2026-11-20T07:00:00Z"); // 10:00 in Riyadh
    static final String PROCESS = "Process_PPC_WeeklyProgressPeriod";
    static final String OPEN_PERIOD = "ServiceTask_OpenProgressPeriod";
    static final String SEND = "UserTask_SendHoursToFms";
    static final String UPLOAD = "UserTask_UploadCompletionReport";
    static final LocalDate W19 = LocalDate.of(2026, 11, 19);
    static final LocalDate W26 = LocalDate.of(2026, 11, 26);
    static final LocalDate W03 = LocalDate.of(2026, 12, 3);
    static final List<String> HEADER = List.of("Type", "Date", "Badge No.", "Employment Type", "Project", "Activity", "Cost Centre", "CBS", "CBS Cost Centre", "Hrs");
    static final String SENT = "Sent to FMS";
    static final String REJECTED = "Rejected by FMS";

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
    ProgressPeriodRepository periods;

    @Autowired
    FmsHoursExportService exportService;

    private BookHoursFixture fixture;
    private FmsIds fms;
    private AssignmentFlow flow;

    /** All test classes share one Camunda: an instance of the weekly process left waiting (by the cron timer or another class) must not reach this class's workers. */
    @BeforeAll
    static void noInstanceLeftWaiting() {
        IntegrationApp.background();
        cancelWaiting();
    }

    private static void cancelWaiting() {
        for (String element : new String[] {OPEN_PERIOD, "ServiceTask_GenerateFmsHoursFile", SEND, "ServiceTask_RecordFmsHandOff", UPLOAD}) {
            CamundaSteps.cancelInstancesWaitingAt(element);
        }
    }

    @BeforeEach
    void background() {
        cancelWaiting();
        clock.set(TODAY);
        fixture = new BookHoursFixture(jdbc);
        BookHoursFixture.Ids ids = fixture.reset();
        fms = fixture.fmsBackground(ids);
        fixture.fmsLines(fms);
        flow = new AssignmentFlow(port, jdbc);
    }

    // --- the scenarios ----------------------------------------------------------------------------------------------------

    @Test
    void BPA58_cutOffCreatesTheWeeklyFile() {
        long instance = cutOff(W19);

        Map<String, Object> period = jdbc.queryForMap("SELECT * FROM bpa.progress_period WHERE week_ending = ?", java.sql.Date.valueOf(W19));
        assertThat(period.get("status")).isEqualTo("CutOff");
        assertThat(((Number) period.get("process_instance_key")).longValue()).isEqualTo(instance);
        List<Map<String, Object>> files = jdbc.queryForList("SELECT * FROM bpa.fms_hours_export WHERE progress_period_id = ?", period.get("id"));
        assertThat(files).hasSize(1);
        assertThat(files.get(0).get("row_count")).isEqualTo(3);
        List<String> groups = new ArrayList<>();
        openTasks(instance, SEND).get(0).path("candidateGroups").forEach(g -> groups.add(g.asText()));
        assertThat(groups).containsExactly("ppc-team");
    }

    @Test
    void BPA58_fileHasTheFmsLayout() {
        cutOff(W19);

        List<List<String>> rows = download("ppc-team", exportOf(W19, null));

        assertThat(rows.get(0)).isEqualTo(HEADER);
        assertThat(rows).contains(List.of("FMS", "16/11/2026", "E1021", "Direct", "PRJ-139", "A-2201", "STRP01", "CBS-139-PIP-01", "Fabrication", "8.00"));
    }

    @Test
    void BPA58_onlyApprovedLinesAreExported() {
        cutOff(W19);

        List<List<String>> rows = download("ppc-team", exportOf(W19, null));

        assertThat(rows).noneMatch(r -> r.get(2).equals("E1021") && r.get(1).equals("17/11/2026"));
        assertThat(rows).noneMatch(r -> r.get(2).equals("E1099") && r.get(1).equals("17/11/2026"));
    }

    @Test
    void BPA58_lineIsExportedOnce() {
        cutOff(W19);
        assertThat(download("ppc-team", exportOf(W19, null))).anyMatch(r -> r.get(2).equals("E1021") && r.get(1).equals("16/11/2026"));

        cutOff(W26);

        assertThat(download("ppc-team", exportOf(W26, null))).noneMatch(r -> r.get(2).equals("E1021") && r.get(1).equals("16/11/2026"));
    }

    @Test
    void BPA58_lateApprovedLineGoesInNextFile() {
        cutOff(W19);
        jdbc.update("UPDATE bpa.digital_timesheet_line SET status = 'Approved' WHERE digital_timesheet_id = ? AND work_date = ?", fms.e1021(),
                java.sql.Date.valueOf(LocalDate.of(2026, 11, 17)));

        cutOff(W26);

        assertThat(download("ppc-team", exportOf(W26, null))).anyMatch(r -> r.get(2).equals("E1021") && r.get(1).equals("17/11/2026"));
    }

    @Test
    void BPA58_blankCbsAndCostCentreStayBlank() {
        cutOff(W19);

        List<List<String>> rows = download("ppc-team", exportOf(W19, null));

        List<String> e1150 = rows.stream().filter(r -> r.get(2).equals("E1150")).findFirst().orElseThrow();
        assertThat(e1150.get(6)).as("Cost Centre").isEmpty();
        assertThat(e1150.get(7)).as("CBS").isEmpty();
        assertThat(e1150.get(8)).as("CBS Cost Centre").isEqualTo("Fabrication");
    }

    @Test
    void BPA58_emptyWeekGivesHeadersOnly() {
        cutOff(W19); // takes every Approved line, so none is waiting for the next cut-off

        cutOff(W26);

        List<List<String>> rows = download("ppc-team", exportOf(W26, null));
        assertThat(rows).containsExactly(HEADER);
        assertThat(jdbc.queryForObject("SELECT row_count FROM bpa.fms_hours_export e JOIN bpa.progress_period p ON p.id = e.progress_period_id WHERE p.week_ending = ?",
                Integer.class, java.sql.Date.valueOf(W26))).isZero();
    }

    @Test
    void BPA58_ppcRecordsTheHandOff() {
        long instance = cutOff(W19);
        UUID export = exportOf(W19, null);

        HttpResponse<String> done = completeSend("ppc-team", instance, List.of(handOff(export, SENT, null)));

        assertThat(done.statusCode()).as(done.body()).isEqualTo(204);
        awaitTask(instance, UPLOAD);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM bpa.fms_hours_export WHERE id = ?", export);
        assertThat(row.get("sent_by")).isEqualTo("ppc-team");
        assertThat(((Timestamp) row.get("sent_at")).toInstant()).isEqualTo(clock.instant());
    }

    @Test
    void BPA58_onlyPpcCanDownloadTheFile() {
        cutOff(W19);
        UUID export = exportOf(W19, null); // the Given: the file exists

        HttpResponse<byte[]> refused = flow.loggedInAs("supervisor").getBytes("/api/v1/fms-hours-exports/" + export + "/file");

        assertThat(refused.statusCode()).isEqualTo(403);
    }

    @Test
    void BPA58_employmentTypeShowsDirectOrAgency() {
        fixture.line(fms.e2001(), LocalDate.of(2026, 11, 18), "Cutting", new BigDecimal("5"), "Approved");
        cutOff(W19);

        List<List<String>> rows = download("ppc-team", exportOf(W19, null));

        assertThat(rows.stream().filter(r -> r.get(2).equals("E2001")).findFirst().orElseThrow().get(3)).isEqualTo("Agency");
        assertThat(rows.stream().filter(r -> r.get(2).equals("E1021")).findFirst().orElseThrow().get(3)).isEqualTo("Direct");
    }

    @Test
    void BPA58_weekAcrossMonthEndGivesTwoFiles() {
        cutOff(W19); // the Background's Approved lines are exported already: only the two new lines wait
        fixture.line(fms.e1021(), LocalDate.of(2026, 11, 29), "Cutting", new BigDecimal("8"), "Approved");
        fixture.line(fms.e1021(), LocalDate.of(2026, 12, 1), "Cutting", new BigDecimal("6"), "Approved");

        cutOff(W03);

        List<Map<String, Object>> files = jdbc.queryForList("""
                SELECT e.id, e.period_month FROM bpa.fms_hours_export e JOIN bpa.progress_period p ON p.id = e.progress_period_id
                WHERE p.week_ending = ? ORDER BY e.period_month""", java.sql.Date.valueOf(W03));
        assertThat(files).hasSize(2);
        assertThat(files.get(0).get("period_month").toString()).isEqualTo("2026-11-01");
        assertThat(files.get(1).get("period_month").toString()).isEqualTo("2026-12-01");
        List<List<String>> november = download("ppc-team", (UUID) files.get(0).get("id"));
        List<List<String>> december = download("ppc-team", (UUID) files.get(1).get("id"));
        assertThat(november.subList(1, november.size())).extracting(r -> r.get(1)).containsExactly("29/11/2026");
        assertThat(december.subList(1, december.size())).extracting(r -> r.get(1)).containsExactly("01/12/2026");
    }

    @Test
    void BPA58_fmsRejectionReleasesLinesToNextFile() {
        long instance = cutOff(W19);
        UUID export = exportOf(W19, null);

        HttpResponse<String> done = completeSend("ppc-team", instance, List.of(handOff(export, REJECTED, "Badge E1099 unknown in FMS")));

        assertThat(done.statusCode()).as(done.body()).isEqualTo(204);
        awaitTask(instance, UPLOAD);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM bpa.fms_hours_export WHERE id = ?", export);
        assertThat(row.get("fms_rejected_reason")).isEqualTo("Badge E1099 unknown in FMS");
        assertThat(((Timestamp) row.get("fms_rejected_at")).toInstant()).isEqualTo(clock.instant());
        cutOff(W26);
        assertThat(download("ppc-team", exportOf(W26, null))).anyMatch(r -> r.get(2).equals("E1099") && r.get(1).equals("16/11/2026"));
    }

    @Test
    void BPA58_fmsRejectionNeedsAReason() {
        long instance = cutOff(W19);
        UUID export = exportOf(W19, null);

        HttpResponse<String> refused = completeSend("ppc-team", instance, List.of(handOff(export, REJECTED, null)));

        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(400);
        assertThat(json(refused.body()).path("detail").asText()).isEqualTo("Reason is required");
        assertThat(openTasks(instance, SEND)).as("the task stays open").hasSize(1);
        assertThat(jdbc.queryForObject("SELECT fms_rejected_at FROM bpa.fms_hours_export WHERE id = ?", Timestamp.class, export)).isNull();
    }

    // --- extras (not Gherkin scenarios) ---------------------------------------------------------------------------------------

    @Test
    void anUnknownFileIs404() {
        HttpResponse<byte[]> missing = flow.loggedInAs("ppc-team").getBytes("/api/v1/fms-hours-exports/" + UUID.randomUUID() + "/file");

        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(json(new String(missing.body())).path("detail").asText()).isEqualTo("File not found");
    }

    @Test
    void theSameStoredBytesAreServedEveryTime() {
        cutOff(W19);
        UUID export = exportOf(W19, null);

        byte[] first = flow.loggedInAs("ppc-team").getBytes("/api/v1/fms-hours-exports/" + export + "/file").body();
        byte[] second = flow.loggedInAs("ppc-team").getBytes("/api/v1/fms-hours-exports/" + export + "/file").body();

        assertThat(second).isEqualTo(first);
        assertThat(AssignmentFlow.sha256(first)).isEqualTo(jdbc.queryForObject("SELECT sha256 FROM bpa.fms_hours_export WHERE id = ?", String.class, export));
    }

    @Test
    void aSecondPeriodForTheSameWeekFailsWithoutCreatingOne() {
        cutOff(W19);
        long stale = startAt(OPEN_PERIOD);

        poll(() -> IntegrationApp.admin.post("/v2/incidents/search", Map.of("filter", Map.of("processInstanceKey", Long.toString(stale)))).body().path("items").size(),
                count -> count > 0, "an incident for the second instance of the same week");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.progress_period WHERE week_ending = ?", Integer.class, java.sql.Date.valueOf(W19))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.fms_hours_export", Integer.class)).isEqualTo(1);
    }

    @Test
    void aReasonOf251CharactersIsRefused() {
        long instance = cutOff(W19);
        UUID export = exportOf(W19, null);

        HttpResponse<String> refused = completeSend("ppc-team", instance, List.of(handOff(export, REJECTED, "x".repeat(251))));

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(json(refused.body()).path("detail").asText()).isEqualTo("Reason can be at most 250 characters");
        assertThat(completeSend("ppc-team", instance, List.of(handOff(export, REJECTED, "x".repeat(250)))).statusCode()).isEqualTo(204);
    }

    @Test
    void aLineDatedAfterTheWeekEndingWaitsForItsOwnCutOff() {
        fixture.line(fms.e1021(), LocalDate.of(2026, 11, 20), "Cutting", new BigDecimal("3"), "Approved");

        cutOff(W19);

        assertThat(download("ppc-team", exportOf(W19, null))).noneMatch(r -> r.get(1).equals("20/11/2026"));
        cutOff(W26);
        assertThat(download("ppc-team", exportOf(W26, null))).anyMatch(r -> r.get(1).equals("20/11/2026"));
    }

    /** Re-run keys (ADR 0007, D65): a retried job runs the same service call again; it must find what the first run wrote and write nothing more. */
    @Test
    void aRerunOfOpenProgressPeriodReturnsThePeriodItOpened() {
        Instant cutOffAt = Instant.parse("2026-11-19T20:59:00Z");

        ProgressPeriodRepository.Period first = periods.open(7_000_001L, W19, cutOffAt, "bpa-service");
        ProgressPeriodRepository.Period again = periods.open(7_000_001L, W19, cutOffAt, "bpa-service");

        assertThat(again).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.progress_period WHERE week_ending = ?", Integer.class, java.sql.Date.valueOf(W19))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.audit_log WHERE table_name = 'progress_period' AND record_id = ?", Integer.class, first.id()))
                .as("audited once, one row per column").isEqualTo(4);
    }

    @Test
    void aRerunOfGenerateBuildsNoSecondFileAndStampsNoLineTwice() {
        cutOff(W19);
        UUID period = jdbc.queryForObject("SELECT id FROM bpa.progress_period WHERE week_ending = ?", UUID.class, java.sql.Date.valueOf(W19));
        UUID file = exportOf(W19, null);

        List<com.lcs.star.bpa.persistence.FmsHoursExportRepository.Export> again = exportService.generate(period, W19, "bpa-service");

        assertThat(again).extracting(e -> e.id()).containsExactly(file);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.fms_hours_export", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.digital_timesheet_line WHERE fms_hours_export_id = ?", Integer.class, file)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.audit_log WHERE table_name = 'digital_timesheet_line' AND column_name = 'exported_at'",
                Integer.class)).as("each line audited once").isEqualTo(3);
    }

    @Test
    void aRerunOfRecordHandOffLeavesTheFirstOutcome() {
        cutOff(W19);
        UUID file = exportOf(W19, null);

        exportService.recordHandOffs(List.of(new FmsHoursExportService.Outcome(file, SENT, null)), "ppc-team");
        exportService.recordHandOffs(List.of(new FmsHoursExportService.Outcome(file, REJECTED, "late duplicate")), "ppc-team");

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM bpa.fms_hours_export WHERE id = ?", file);
        assertThat(row.get("sent_by")).isEqualTo("ppc-team");
        assertThat(row.get("fms_rejected_at")).as("the second run changed nothing").isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.digital_timesheet_line WHERE fms_hours_export_id = ?", Integer.class, file))
                .as("no line was released").isEqualTo(3);
    }

    // --- helpers ----------------------------------------------------------------------------------------------------------------

    /** The cut-off for the week ending {@code weekEnding} fires: Thursday 23:59:30 in Riyadh (20:59:30 UTC), then the weekly process runs to Send hours to FMS. */
    private long cutOff(LocalDate weekEnding) {
        clock.set(weekEnding.atTime(20, 59, 30).toInstant(java.time.ZoneOffset.UTC));
        long instance = startAt(OPEN_PERIOD);
        awaitTask(instance, SEND);
        return instance;
    }

    private long startAt(String elementId) {
        Reply started = IntegrationApp.admin.post("/v2/process-instances", Map.of(
                "processDefinitionId", PROCESS, "startInstructions", List.of(Map.of("elementId", elementId))));
        assertThat(started.status()).as("start at " + elementId + ": " + started.body()).isEqualTo(200);
        return Long.parseLong(started.body().path("processInstanceKey").asText());
    }

    private String awaitTask(long instance, String elementId) {
        return poll(() -> openTasks(instance, elementId).stream().findFirst(), found -> found.isPresent(), elementId + " of " + instance).orElseThrow()
                .path("userTaskKey").asText();
    }

    private static Map<String, Object> handOff(UUID export, String outcome, String reason) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("fmsHoursExportId", export.toString());
        entry.put("outcome", outcome);
        if (reason != null) {
            entry.put("reason", reason);
        }
        return entry;
    }

    private HttpResponse<String> completeSend(String user, long instance, List<Map<String, Object>> handOffs) {
        String task = openTasks(instance, SEND).get(0).path("userTaskKey").asText();
        return flow.loggedInAs(user).post("/api/v1/tasks/" + task + "/complete", toJson(Map.of("variables", Map.of("fmsHandOffs", handOffs))));
    }

    /** The id of the file of that week (and month, first of the month, when the week has two). */
    private UUID exportOf(LocalDate weekEnding, LocalDate month) {
        return jdbc.queryForObject("""
                SELECT e.id FROM bpa.fms_hours_export e JOIN bpa.progress_period p ON p.id = e.progress_period_id
                WHERE p.week_ending = ? AND (?::date IS NULL OR e.period_month = ?::date) ORDER BY e.period_month LIMIT 1""", UUID.class,
                java.sql.Date.valueOf(weekEnding), month == null ? null : java.sql.Date.valueOf(month), month == null ? null : java.sql.Date.valueOf(month));
    }

    /** The file as the user downloads it, read with the formatted value of every cell of the first ten columns (a blank cell reads ""). */
    private List<List<String>> download(String user, UUID export) {
        HttpResponse<byte[]> response = flow.loggedInAs(user).getBytes("/api/v1/fms-hours-exports/" + export + "/file");
        assertThat(response.statusCode()).as("download as " + user).isEqualTo(200);
        DataFormatter formatter = new DataFormatter();
        List<List<String>> rows = new ArrayList<>();
        try (Workbook book = new XSSFWorkbook(new ByteArrayInputStream(response.body()))) {
            Sheet sheet = book.getSheet("FMS Hours");
            assertThat(sheet).as("sheet FMS Hours").isNotNull();
            for (Row row : sheet) {
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
}
