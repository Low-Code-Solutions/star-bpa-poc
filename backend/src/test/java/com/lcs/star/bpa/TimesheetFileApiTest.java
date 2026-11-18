package com.lcs.star.bpa;

import static com.lcs.star.bpa.CamundaSteps.json;
import static com.lcs.star.bpa.TimesheetFileBuilder.timesheet;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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
 * BPA-52 (E2-03b): the time sheet upload, {@code POST /api/v1/timesheet-files}. One test per Gherkin scenario, named as in the Jira Definition of Done;
 * the Scenario Outline's five rows run inside its test. Today is 18 Nov 2026 (Asia/Riyadh); the Background is BPA-50's ({@link BookHoursFixture}:
 * WP-000457 with Cutting 300 h and Welding 200 h, E1021 and E1099 assigned) plus WP-000461 (Cutting 100 h, E1021 assigned). Every file is built by
 * {@link TimesheetFileBuilder}: tests never use the demo files in templates/demo. Rows are checked by the form's own code, so only what is new
 * (reading, outcomes, logging, access) is asserted in detail here; the line rules have their tests in BookHoursApiTest and HoursBudgetApiTest.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(AssignmentFileApiTest.TestClockConfig.class)
class TimesheetFileApiTest {

    static final Instant TODAY = Instant.parse("2026-11-18T06:00:00Z"); // 09:00 in Riyadh
    static final String UPLOAD = "/api/v1/timesheet-files";
    static final LocalDate D16 = LocalDate.of(2026, 11, 16);
    static final LocalDate D17 = LocalDate.of(2026, 11, 17);

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

    @BeforeAll
    static void camundaIsUp() {
        IntegrationApp.background();
    }

    @BeforeEach
    void background() {
        clock.set(TODAY);
        fixture = new BookHoursFixture(jdbc);
        ids = fixture.reset();
        flow = new AssignmentFlow(port, jdbc);
    }

    // --- the scenarios ----------------------------------------------------------------------------------------------------

    @Test
    void BPA52_validFileSavesAllRows() {
        byte[] file = validRows().xlsx(); // one build: an .xlsx written twice differs in its bytes

        HttpResponse<String> response = upload("supervisor", "team-week47.xlsx", file);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(outcomes(response)).containsExactly("Saved", "Saved", "Saved");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.digital_timesheet_line WHERE status = 'Draft' AND entry_source = 'Upload' AND timesheet_file_id IS NOT NULL",
                Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT sha256 FROM bpa.timesheet_file", String.class)).containsExactly(AssignmentFlow.sha256(file));
    }

    @Test
    void BPA52_oneFileCoversTwoWorkPacks() {
        fixture.workPack461();

        HttpResponse<String> response = upload("supervisor", "two-workpacks.xlsx", timesheet()
                .row("WP-000457", "E1021", "17/11/2026", "Cutting", 6)
                .row("WP-000461", "E1021", "17/11/2026", "Cutting", 4).xlsx());

        assertThat(outcomes(response)).containsExactly("Saved", "Saved");
    }

    @Test
    void BPA52_badRowsAreRejectedGoodRowsSaved() {
        HttpResponse<String> response = upload("supervisor", "mixed.xlsx", timesheet()
                .row("WP-000457", "E1021", "16/11/2026", "Cutting", 8)
                .row("WP-999999", "E1021", "16/11/2026", "Cutting", 8)
                .row("WP-000457", "E1150", "16/11/2026", "Cutting", 8)
                .row("WP-000457", "E1099", "16/11/2026", "Finishing", 8)
                .row("WP-000457", "E1099", "20/11/2026", "Cutting", 8)
                .row("WP-000457", "E1099", "16/11/2026", "Cutting", "abc").xlsx());

        assertThat(outcomes(response)).containsExactly("Saved", "Work Pack not found", "Worker not assigned to this Work Pack",
                "Phase Finishing is not on this Work Pack", "Work date cannot be in the future", "Hours must be a number");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.digital_timesheet_line", Integer.class)).as("only the first row's line").isEqualTo(1);
        assertThat(lineCount("E1021")).isEqualTo(1);
    }

    @Test
    void BPA52_existingLineIsNotOverwritten() {
        UUID line = fixture.line(ids.timesheet("E1021"), D16, "Cutting", new BigDecimal("8"), "Draft");

        HttpResponse<String> response = upload("supervisor", "again.xlsx", timesheet().row("WP-000457", "E1021", "16/11/2026", "Cutting", 6).xlsx());

        assertThat(outcomes(response)).containsExactly("Hours already booked for E1021 on 16 Nov 2026 for phase Cutting");
        assertThat(jdbc.queryForObject("SELECT hours FROM bpa.digital_timesheet_line WHERE id = ?", BigDecimal.class, line)).isEqualByComparingTo("8");
    }

    @Test
    void BPA52_sameKeyTwiceInFileSecondRejected() {
        HttpResponse<String> response = upload("supervisor", "twice.xlsx", timesheet()
                .row("WP-000457", "E1021", "16/11/2026", "Cutting", 4)
                .row("WP-000457", "E1021", "16/11/2026", "Cutting", 5).xlsx());

        assertThat(outcomes(response)).containsExactly("Saved", "Hours already booked for E1021 on 16 Nov 2026 for phase Cutting");
        assertThat(jdbc.queryForObject("SELECT hours FROM bpa.digital_timesheet_line WHERE digital_timesheet_id = ?", BigDecimal.class, ids.timesheet("E1021"))).isEqualByComparingTo("4");
    }

    @Test
    void BPA52_rowOverPhaseBudgetIsRejected() {
        UUID ts461 = fixture.workPack461();
        fixture.fill(ts461, "Cutting", new BigDecimal("96"), "Draft"); // Cutting on WP-000461 has 96 h

        HttpResponse<String> response = upload("supervisor", "budget.xlsx", timesheet().row("WP-000461", "E1021", "17/11/2026", "Cutting", 5).xlsx());

        assertThat(outcomes(response)).containsExactly("Phase Cutting budget of 100 h would be exceeded");
    }

    @Test
    void BPA52_rowsAppliedInFileOrder() {
        fixture.workPack461();
        fixture.line(ids.timesheet("E1021"), D17, "Cutting", new BigDecimal("8"), "Draft"); // E1021 has 8 h on 17 Nov

        HttpResponse<String> response = upload("supervisor", "order.xlsx", timesheet()
                .row("WP-000457", "E1021", "17/11/2026", "Welding", 6)
                .row("WP-000461", "E1021", "17/11/2026", "Cutting", 3).xlsx());

        assertThat(outcomes(response)).containsExactly("Saved", "Worker E1021 would exceed 16 hours on 17 Nov 2026");
    }

    @Test
    void BPA52_requiredColumnsChecked() {
        // the Outline: one row per column that is empty
        HttpResponse<String> response = upload("supervisor", "required.xlsx", timesheet()
                .row(null, "E1021", "16/11/2026", "Cutting", 8)
                .row("WP-000457", null, "16/11/2026", "Cutting", 8)
                .row("WP-000457", "E1021", null, "Cutting", 8)
                .row("WP-000457", "E1021", "16/11/2026", null, 8)
                .row("WP-000457", "E1021", "16/11/2026", "Cutting", null).xlsx());

        assertThat(outcomes(response)).containsExactly("Workpack number is required", "Badge No is required", "Work date is required",
                "Phase is required", "Hours is required");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.digital_timesheet_line", Integer.class)).isZero();
    }

    @Test
    void BPA52_csvFileIsAccepted() {
        HttpResponse<String> response = upload("supervisor", "team-week47.csv", validRows().csv());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(outcomes(response)).containsExactly("Saved", "Saved", "Saved");
    }

    @Test
    void BPA52_rowsForWorkPackInApprovalAreRejected() {
        // ASSUMPTION (plan Q4): a Work Pack is in approval when it has an hours_submission at an approval stage; BPA-53 will write them
        jdbc.update("""
                INSERT INTO bpa.hours_submission (workpack_id, process_instance_key, round_no, submitted_by, submitted_at, stage, created_by, updated_by)
                VALUES (?, 9000000010, 1, 'test', now(), 'Timekeeper', 'test', 'test')""", ids.workpackId());

        HttpResponse<String> response = upload("supervisor", "approval.xlsx", timesheet().row("WP-000457", "E1021", "17/11/2026", "Cutting", 6).xlsx());

        assertThat(outcomes(response)).containsExactly("Hours for WP-000457 are in approval; book them after the decision");
        assertThat(lineCount("E1021")).isZero();
    }

    @Test
    void BPA52_remarkOver250IsRejected() {
        HttpResponse<String> response = upload("supervisor", "remark.xlsx",
                timesheet().row("WP-000457", "E1021", "16/11/2026", "Cutting", 8, "x".repeat(251)).xlsx());

        assertThat(outcomes(response)).containsExactly("Remarks can be at most 250 characters");
        assertThat(json(response.body()).path("rows").get(0).path("remarks").asText()).as("the log keeps the remark as uploaded").hasSize(251);
    }

    @Test
    void BPA52_fileAbove5MbIsRefused() {
        HttpResponse<String> response = upload("supervisor", "big.xlsx", new byte[6 * 1024 * 1024]);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(json(response.body()).path("detail").asText()).isEqualTo("File is larger than 5 MB");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.timesheet_file", Integer.class)).isZero();
    }

    @Test
    void BPA52_unreadableFileIsRejected() {
        HttpResponse<String> response = upload("supervisor", "notes.xlsx", TimesheetFileBuilder.notASpreadsheet());

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(json(response.body()).path("outcome").asText()).isEqualTo("File cannot be read");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.digital_timesheet_line", Integer.class)).isZero();
    }

    @Test
    void BPA52_emptyFileIsRejected() {
        HttpResponse<String> response = upload("supervisor", "empty.xlsx", timesheet().xlsx()); // the template with no data rows

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(json(response.body()).path("outcome").asText()).isEqualTo("File has no rows");
    }

    @Test
    void BPA52_resultCanBeDownloaded() {
        String id = json(upload("supervisor", "team-week47.xlsx", validRows().xlsx()).body()).path("id").asText();

        HttpResponse<String> result = flow.loggedInAs("supervisor").get(UPLOAD + "/" + id + "/result");
        assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
        JsonNode rows = json(result.body()).path("rows");
        assertThat(rows).hasSize(3);
        assertThat(rows.get(2).path("workpackNumber").asText()).isEqualTo("WP-000457");
        assertThat(rows.get(2).path("badgeNo").asText()).isEqualTo("E1099");
        assertThat(rows.get(2).path("workDate").asText()).as("values as uploaded").isEqualTo("17/11/2026");
        assertThat(rows.get(2).path("phase").asText()).isEqualTo("Welding");
        assertThat(rows.get(2).path("hours").asText()).isEqualTo("7.5");
        assertThat(rows.get(2).path("outcome").asText()).isEqualTo("Saved");

        HttpResponse<String> csv = flow.loggedInAs("supervisor").getWithAccept(UPLOAD + "/" + id + "/result", "text/csv");
        assertThat(csv.statusCode()).isEqualTo(200);
        assertThat(csv.body()).startsWith("rowNumber,workpackNumber,badgeNo,workDate,phase,hours,remarks,outcome").contains("WP-000457,E1099,17/11/2026,Welding,7.5,,Saved");
    }

    @Test
    void BPA52_otherPersonasCannotUpload() {
        HttpResponse<String> response = upload("timekeeper", "team-week47.xlsx", validRows().xlsx());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.timesheet_file", Integer.class)).isZero();
    }

    @Test
    void BPA52_aProductionTeamMemberCanAlsoUploadTheTimeSheet() {
        // like timesheet-demo-good.xlsx, but for WP-000457 and built here: tests never use the demo files
        HttpResponse<String> response = upload("production-team", "good.xlsx", validRows().xlsx());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(outcomes(response)).containsExactly("Saved", "Saved", "Saved");
        assertThat(jdbc.queryForList("SELECT DISTINCT created_by FROM bpa.digital_timesheet_line WHERE status = 'Draft' AND entry_source = 'Upload'", String.class))
                .containsExactly("production-team");
    }

    // --- not scenarios ---------------------------------------------------------------------------------------------------------

    @Test
    void headersAreMatchedWithoutRegardToCaseOrOrder() {
        // plan Q1: the Gherkin writes "Workpack Number" and "Work Date", the template "Workpack number" and "Work date"
        HttpResponse<String> response = upload("supervisor", "cases.xlsx", timesheet()
                .headers("WORKPACK NUMBER", "badge no", "Work Date", "PHASE", "hours", "Remarks")
                .row("WP-000457", "E1021", "16/11/2026", "Cutting", 8).xlsx());

        assertThat(outcomes(response)).containsExactly("Saved");
    }

    @Test
    void aFileWithoutTheHoursColumnIsNotRecognised() {
        HttpResponse<String> response = upload("supervisor", "layout.xlsx", timesheet()
                .headers("Workpack number", "Badge No", "Work date", "Phase")
                .row("WP-000457", "E1021", "16/11/2026", "Cutting", 8).xlsx());

        assertThat(json(response.body()).path("outcome").asText()).isEqualTo("File layout not recognised");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.digital_timesheet_line", Integer.class)).isZero();
    }

    @Test
    void theTemplateCanBeDownloaded() {
        HttpResponse<byte[]> template = flow.loggedInAs("production-team").getBytes(UPLOAD + "/template");

        assertThat(template.statusCode()).isEqualTo(200);
        assertThat(template.body()).as("an .xlsx is a zip").startsWith((byte) 'P', (byte) 'K');
    }

    // --- helpers ----------------------------------------------------------------------------------------------------------

    /** The rows of the valid-file scenario: E1021 and E1099 on 16 Nov, E1099 7.5 h of Welding on 17 Nov. */
    private static TimesheetFileBuilder validRows() {
        return timesheet()
                .row("WP-000457", "E1021", "16/11/2026", "Cutting", 8)
                .row("WP-000457", "E1099", "16/11/2026", "Cutting", 8)
                .row("WP-000457", "E1099", "17/11/2026", "Welding", 7.5);
    }

    private HttpResponse<String> upload(String persona, String fileName, byte[] file) {
        return flow.loggedInAs(persona).postFile(UPLOAD, fileName, file);
    }

    private static List<String> outcomes(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        List<String> outcomes = new ArrayList<>();
        json(response.body()).path("rows").forEach(r -> outcomes.add(r.path("outcome").asText()));
        return outcomes;
    }

    private int lineCount(String badge) {
        return jdbc.queryForObject("SELECT count(*) FROM bpa.digital_timesheet_line WHERE digital_timesheet_id = ?", Integer.class, ids.timesheet(badge));
    }
}
