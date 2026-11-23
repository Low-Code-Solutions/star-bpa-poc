package com.lcs.star.bpa;

import static com.lcs.star.bpa.CamundaSteps.json;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.lcs.star.bpa.api.TestBrowser;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
 * BPA-72 (E2-22): {@code GET /api/v1/workpacks}, the read-only Work Packs list. One test per Gherkin scenario that has a backend part, named as the React
 * tests are (the "open a Work Pack from the list" scenario is a navigation and has no API part); the extras are ours (plan BPA-72, Questions 1 to 5). The
 * Background is built with SQL on top of {@link BookHoursFixture}: WP-000457 (In Progress), WP-000461 (Completed) and WP-000480 (Released) on activity
 * A-2201 (project PRJ-139), each with a planned period (ASSUMPTION data) and assignment state Assigned. Today is 18 Nov 2026 (Asia/Riyadh).
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(AssignmentFileApiTest.TestClockConfig.class)
class WorkPackListApiTest {

    static final Instant TODAY = Instant.parse("2026-11-18T06:00:00Z");
    static final String LIST = "/api/v1/workpacks";
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

    private AssignmentFlow flow;

    @BeforeEach
    void background() {
        clock.set(TODAY);
        jdbc.update("DELETE FROM bpa.workpack_schedule"); // BookHoursFixture.reset() does not clear it and the foreign key would stop the delete of the Work Packs
        BookHoursFixture fixture = new BookHoursFixture(jdbc);
        fixture.reset();
        fixture.completionBackground(); // WP-000457 and WP-000461 In Progress, WP-000480 Released
        jdbc.update("UPDATE bpa.workpack SET status = 'Completed' WHERE workpack_number = 'WP-000461'");
        schedule("WP-000457", "2026-11-03", "2026-11-06");
        schedule("WP-000461", "2026-11-10", "2026-11-12");
        schedule("WP-000480", "2026-11-16", "2026-11-18");
        flow = new AssignmentFlow(port, jdbc);
    }

    // --- the scenarios (the backend part of each) --------------------------------------------------------------------------------

    @Test
    void BPA72_listShowsEveryWorkPack() {
        HttpResponse<String> response = flow.loggedInAs("ppc-team").get(LIST);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        List<JsonNode> rows = rows(response);
        assertThat(rows).extracting(r -> r.path("workpackNumber").asText()).containsExactly("WP-000480", "WP-000461", "WP-000457");
        JsonNode in457 = row(rows, "WP-000457");
        assertThat(in457.path("status").asText()).isEqualTo("In Progress");
        assertThat(in457.path("assignmentState").asText()).isEqualTo("Assigned");
        assertThat(in457.path("plannedStartDate").asText()).isEqualTo("2026-11-03");
        assertThat(in457.path("plannedEndDate").asText()).isEqualTo("2026-11-06");
        assertThat(in457.path("activityRef").asText()).isEqualTo("A-2201");
        assertThat(in457.path("drawingRef").asText()).isEqualTo("DWG-118");
        assertThat(in457.path("departmentRef").asText()).isEqualTo("Fabrication");
        assertThat(in457.path("projectRef").asText()).isEqualTo("PRJ-139");
        assertThat(row(rows, "WP-000461").path("status").asText()).isEqualTo("Completed");
        assertThat(row(rows, "WP-000480").path("status").asText()).isEqualTo("Released");
        assertThat(in457.path("approvedPct").isNull()).as("no week is locked yet").isTrue();
    }

    @Test
    void BPA72_filterByStatus() {
        HttpResponse<String> response = flow.loggedInAs("ppc-team").get(LIST + "?status=In%20Progress");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(rows(response)).extracting(r -> r.path("workpackNumber").asText()).containsExactly("WP-000457");
    }

    @Test
    void BPA72_searchByNumber() {
        HttpResponse<String> response = flow.loggedInAs("ppc-team").get(LIST + "?search=WP-000461");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        List<JsonNode> rows = rows(response);
        assertThat(rows).extracting(r -> r.path("workpackNumber").asText()).containsExactly("WP-000461");
        assertThat(rows.get(0).path("status").asText()).isEqualTo("Completed");
    }

    @Test
    void BPA72_unknownNumber() {
        HttpResponse<String> response = flow.loggedInAs("ppc-team").get(LIST + "?search=WP-999999");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(rows(response)).isEmpty();
    }

    @Test
    void BPA72_anyPersonaReadsWithoutActions() {
        for (String persona : PERSONAS) {
            HttpResponse<String> response = flow.loggedInAs(persona).get(LIST);
            assertThat(response.statusCode()).as(persona + ": " + response.body()).isEqualTo(200);
            assertThat(rows(response)).as(persona).hasSize(3);
        }
        // read only: the one action on the path stays the Analyst's
        assertThat(flow.loggedInAs("timekeeper").post(LIST, "").statusCode()).isEqualTo(403);
        assertThat(flow.loggedInAs("timekeeper").delete(LIST).statusCode()).as("no delete").isIn(403, 405);
    }

    // --- not scenarios (plan BPA-72, Questions 1 to 5) -----------------------------------------------------------------------------

    @Test
    void severalStatusesAreOrTogether() {
        HttpResponse<String> response = flow.loggedInAs("ppc-team").get(LIST + "?status=Released&status=Completed");

        assertThat(rows(response)).extracting(r -> r.path("workpackNumber").asText()).containsExactly("WP-000480", "WP-000461");
    }

    @Test
    void searchIsPartialAndAnyCase() {
        assertThat(rows(flow.loggedInAs("ppc-team").get(LIST + "?search=461"))).extracting(r -> r.path("workpackNumber").asText()).containsExactly("WP-000461");
        assertThat(rows(flow.loggedInAs("ppc-team").get(LIST + "?search=wp-000457"))).extracting(r -> r.path("workpackNumber").asText()).containsExactly("WP-000457");
    }

    @Test
    void filterByProject() {
        assertThat(rows(flow.loggedInAs("ppc-team").get(LIST + "?project=PRJ-139"))).hasSize(3);
        assertThat(rows(flow.loggedInAs("ppc-team").get(LIST + "?project=PRJ-140"))).as("A-2201 is on PRJ-139").isEmpty();
    }

    @Test
    void filterByWeekEnding() {
        // Q4 (ASSUMPTION): the Work Packs that have a progress row for that week
        UUID week = period("2026-11-19", "CutOff");
        progress("WP-000457", week, "40.00");

        assertThat(rows(flow.loggedInAs("ppc-team").get(LIST + "?weekEnding=2026-11-19"))).extracting(r -> r.path("workpackNumber").asText()).containsExactly("WP-000457");
        assertThat(rows(flow.loggedInAs("ppc-team").get(LIST + "?weekEnding=2026-11-26"))).isEmpty();
    }

    @Test
    void approvedPctAndEarnedManhoursAreTheLatestLockedWeeks() {
        // Q3 and Q5: the latest Locked week's approved % of the Work Pack and the Earned man-hours of its activity; an open (CutOff) week is not shown
        UUID w12 = period("2026-11-12", "Locked");
        UUID w19 = period("2026-11-19", "CutOff");
        progress("WP-000457", w12, "30.00");
        progress("WP-000457", w19, "60.00");
        activity("A-2201", w12, "30.00", "150.00");
        activity("A-2201", w19, "60.00", "300.00");

        JsonNode in457 = row(rows(flow.loggedInAs("ppc-team").get(LIST)), "WP-000457");

        assertThat(in457.path("approvedPct").decimalValue()).isEqualByComparingTo("30.00");
        assertThat(in457.path("approvedWeekEnding").asText()).isEqualTo("2026-11-12");
        assertThat(in457.path("earnedManhours").decimalValue()).isEqualByComparingTo("150.00");
        assertThat(row(rows(flow.loggedInAs("ppc-team").get(LIST)), "WP-000480").path("approvedPct").isNull()).as("no row for WP-000480").isTrue();
    }

    @Test
    void aWorkPackNotScheduledYetHasNoDates() {
        jdbc.update("DELETE FROM bpa.workpack_schedule WHERE workpack_id = (SELECT id FROM bpa.workpack WHERE workpack_number = 'WP-000480')");

        JsonNode row = row(rows(flow.loggedInAs("ppc-team").get(LIST)), "WP-000480");

        assertThat(row.path("plannedStartDate").isNull()).isTrue();
        assertThat(row.path("plannedEndDate").isNull()).isTrue();
    }

    @Test
    void notLoggedInIs401() {
        assertThat(new TestBrowser(port).get(LIST).statusCode()).isEqualTo(401);
    }

    @Test
    void anUnknownStatusIs400() {
        HttpResponse<String> response = flow.loggedInAs("ppc-team").get(LIST + "?status=Finished");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
    }

    // --- helpers ----------------------------------------------------------------------------------------------------------------

    private void schedule(String number, String start, String end) {
        jdbc.update("""
                INSERT INTO bpa.workpack_schedule (workpack_id, planned_start_date, planned_end_date, scheduled_by, created_by, updated_by)
                SELECT id, ?::date, ?::date, 'test', 'test', 'test' FROM bpa.workpack WHERE workpack_number = ?""", start, end, number);
    }

    /** A progress period of that week (the 12 Nov one {@link BookHoursFixture#reset()} made is changed instead of inserted twice). */
    private UUID period(String weekEnding, String status) {
        jdbc.update("""
                INSERT INTO bpa.progress_period (week_ending, cut_off_at, status, locked_at, process_instance_key, created_by, updated_by)
                VALUES (?::date, ?::date + TIME '20:59' AT TIME ZONE 'UTC', ?, CASE WHEN ? = 'Locked' THEN now() END, ?, 'test', 'test')
                ON CONFLICT (week_ending) DO UPDATE SET status = EXCLUDED.status, locked_at = EXCLUDED.locked_at""", weekEnding, weekEnding, status, status, "2026-11-19".equals(weekEnding) ? 9000000019L : 9000000002L);
        return jdbc.queryForObject("SELECT id FROM bpa.progress_period WHERE week_ending = ?::date", UUID.class, weekEnding);
    }

    private void progress(String number, UUID period, String approvedPct) {
        jdbc.update("""
                INSERT INTO bpa.workpack_progress (workpack_id, progress_period_id, approved_pct, approved_by, approved_at, created_by, updated_by)
                SELECT id, ?, ?::numeric, 'ppc-team', now(), 'test', 'test' FROM bpa.workpack WHERE workpack_number = ?""", period, approvedPct, number);
    }

    private void activity(String activityRef, UUID period, String actualPct, String earned) {
        jdbc.update("""
                INSERT INTO bpa.activity_progress (activity_ref, progress_period_id, actual_pct, budgeted_units, earned_manhours, created_by, updated_by)
                VALUES (?, ?, ?::numeric, 500, ?::numeric, 'test', 'test')""", activityRef, period, actualPct, earned);
    }

    private static List<JsonNode> rows(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        List<JsonNode> rows = new ArrayList<>();
        json(response.body()).forEach(rows::add);
        return rows;
    }

    private static JsonNode row(List<JsonNode> rows, String number) {
        return rows.stream().filter(r -> number.equals(r.path("workpackNumber").asText())).findFirst().orElseThrow(() -> new AssertionError(number + " is not in the list"));
    }
}
