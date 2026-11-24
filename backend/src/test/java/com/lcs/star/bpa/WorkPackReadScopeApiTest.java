package com.lcs.star.bpa;

import static com.lcs.star.bpa.CamundaSteps.json;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.lcs.star.bpa.api.TestBrowser;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BPA-79 (E1 portal): the Work Packs list shows a user only the Work Packs of the user's projects, and the three Work Pack detail reads of BPA-73 (the detail with
 * its tabs, the history and the open tasks) answer 403 for a Work Pack of another project; a number that does not exist is still 404. One test per Gherkin scenario,
 * named as in the plan (Jira gives no names; docs/04); the extras are ours (plan BPA-79). The Background is built here: the eleven personas as app_user rows,
 * workpack-owner with PRJ-139 only, WP-000457 (on A-2201, PRJ-139, from BookHoursFixture) and WP-000500 on A-4201 of PRJ-142 (not seeded; WBS 14201). The one rule is
 * ProjectScope (BPA-77): a user_project row, the same for every persona, ppc-team included.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkPackReadScopeApiTest {

    static final String LIST = "/api/v1/workpacks";
    static final String DETAIL = "/api/v1/workpacks/";
    static final String MINE = "WP-000457"; // PRJ-139
    static final String OTHER = "WP-000500"; // PRJ-142
    static final String REFUSED = "Project PRJ-142 is not assigned to you";

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

    private AssignmentFlow flow;

    @BeforeEach
    void background() {
        flow = new AssignmentFlow(port, jdbc);
        new RevisionFlow(port, jdbc).seedAppUsers(); // the eleven personas, as the S22 sync writes them (the sync of IntegrationApp has its own database)
        new BookHoursFixture(jdbc).reset(); // WP-000457 on A-2201 (PRJ-139)
        jdbc.update("INSERT INTO bpa.project_job_code (project_ref, job_code_ref, created_by, updated_by) VALUES ('PRJ-142', 'JC-142', 'test', 'test') ON CONFLICT (project_ref) DO NOTHING");
        jdbc.update("INSERT INTO bpa.wbs (wbs_ref, project_ref, created_by, updated_by) VALUES ('14201', 'PRJ-142', 'test', 'test') ON CONFLICT (wbs_ref) DO NOTHING");
        jdbc.update("""
                INSERT INTO bpa.activity (activity_ref, activity_name, wbs_ref, created_by, updated_by)
                VALUES ('A-4201', 'Synthetic piping module M-42', '14201', 'test', 'test') ON CONFLICT (activity_ref) DO NOTHING""");
        jdbc.update("""
                INSERT INTO bpa.workpack (workpack_number, activity_ref, drawing_ref, department_ref, completion_date, status, assignment_state, process_instance_key,
                                          created_by, updated_by)
                VALUES (?, 'A-4201', 'DWG-118', 'Fabrication', DATE '2026-11-30', 'Released', 'Assigned', 9000000500, 'test', 'test')""", OTHER);
        jdbc.update("DELETE FROM bpa.user_project");
        assign("workpack-owner", "PRJ-139"); // "has PRJ-139 only"
    }

    // --- the scenarios ----------------------------------------------------------------------------------------------------

    @Test
    void BPA79_listFilteredByProject() {
        TestBrowser owner = flow.loggedInAs("workpack-owner");

        HttpResponse<String> response = owner.get(LIST);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(numbers(json(response.body()))).as("only PRJ-139 Work Packs are listed").containsExactly(MINE);
    }

    @Test
    void BPA79_directLinkToOtherProjectIsRefused() {
        TestBrowser owner = flow.loggedInAs("workpack-owner");

        HttpResponse<String> response = owner.get(DETAIL + OTHER);

        assertRefused(response, 403, REFUSED);
    }

    // --- not scenarios (plan BPA-79): one per BPA-73 read, and the edges ------------------------------------------------------

    @Test
    void theHistoryOfAnotherProjectsWorkPackIsRefused() {
        assertRefused(flow.loggedInAs("workpack-owner").get(DETAIL + OTHER + "/history"), 403, REFUSED);
    }

    @Test
    void theOpenTasksOfAnotherProjectsWorkPackAreRefused() {
        assertRefused(flow.loggedInAs("workpack-owner").get(DETAIL + OTHER + "/open-tasks"), 403, REFUSED);
    }

    /** A number that does not exist is 404 "Work Pack not found", not 403 (the check runs after the lookup); a malformed one is 400. Guards. */
    @Test
    void anUnknownNumberIsStillNotFound() {
        TestBrowser owner = flow.loggedInAs("workpack-owner");

        assertRefused(owner.get(DETAIL + "WP-999999"), 404, "Work Pack not found");
        assertRefused(owner.get(DETAIL + "WP-999999/history"), 404, "Work Pack not found");
        assertRefused(owner.get(DETAIL + "WP-999999/open-tasks"), 404, "Work Pack not found");
        assertThat(owner.get(DETAIL + "WP-12").statusCode()).isEqualTo(400);
    }

    /** The user's own project: all three reads open. A guard (passes before the scope code). */
    @Test
    void myProjectsWorkPackOpensWithItsTabs() {
        TestBrowser owner = flow.loggedInAs("workpack-owner");

        assertThat(owner.get(DETAIL + MINE).statusCode()).isEqualTo(200);
        assertThat(owner.get(DETAIL + MINE + "/history").statusCode()).isEqualTo(200);
        assertThat(owner.get(DETAIL + MINE + "/open-tasks").statusCode()).isEqualTo(200);
    }

    @Test
    void aUserWithNoProjectsSeesAnEmptyList() {
        TestBrowser supervisor = flow.loggedInAs("supervisor"); // no user_project rows

        HttpResponse<String> response = supervisor.get(LIST);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(json(response.body())).as("an empty list, not an error").isEmpty();
    }

    /** The project filter is the user's own choice within the user's projects: it cannot widen the scope. */
    @Test
    void theProjectFilterCannotWidenTheScope() {
        TestBrowser owner = flow.loggedInAs("workpack-owner");

        assertThat(numbers(json(owner.get(LIST + "?project=PRJ-142").body()))).as("PRJ-142 is not the user's: an empty list, not a 403").isEmpty();
        assertThat(numbers(json(owner.get(LIST + "?project=PRJ-139").body()))).containsExactly(MINE);
    }

    /** One rule for every persona (BPA-77): ppc-team with no rows sees nothing and is refused like any other user. */
    @Test
    void thePpcTeamHasNoException() {
        TestBrowser ppc = flow.loggedInAs("ppc-team"); // no user_project rows

        assertThat(json(ppc.get(LIST).body())).isEmpty();
        assertRefused(ppc.get(DETAIL + MINE), 403, "Project PRJ-139 is not assigned to you");
    }

    // --- helpers ----------------------------------------------------------------------------------------------------------

    private void assign(String username, String projectRef) {
        jdbc.update("INSERT INTO bpa.user_project (username, project_ref, created_by, updated_by) VALUES (?, ?, 'test', 'test') ON CONFLICT (username, project_ref) DO NOTHING",
                username, projectRef);
    }

    private static List<String> numbers(JsonNode rows) {
        List<String> values = new ArrayList<>();
        rows.forEach(r -> values.add(r.path("workpackNumber").asText()));
        return values;
    }

    private static void assertRefused(HttpResponse<String> response, int status, String message) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(json(response.body()).path("detail").asText()).isEqualTo(message);
    }
}
