package com.lcs.star.bpa;

import static com.lcs.star.bpa.CamundaSteps.json;
import static com.lcs.star.bpa.CamundaSteps.toJson;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.lcs.star.bpa.api.TestBrowser;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * BPA-76 (E1 portal administration): the PPC Team assigns projects to a portal user and removes them ({@code user_project}), through
 * {@code GET /api/v1/users}, {@code GET /api/v1/projects}, {@code GET|POST /api/v1/users/{username}/projects} and
 * {@code DELETE /api/v1/users/{username}/projects/{projectRef}}, group ppc-team only. One test per Gherkin scenario, named as in the plan (Jira gives no names;
 * docs/04); the extras are ours (plan BPA-76, Questions and Resolutions). The Background is built here: the eleven personas as {@code app_user} rows, and the
 * project PRJ-142, which the seed does not have, as a fixture; PRJ-139 and PRJ-140 come from the seed. Nothing is scoped by the rows yet (BPA-77 to BPA-79).
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UserProjectApiTest {

    static final String ENGINEER = "project-engineer";

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
        jdbc.update("DELETE FROM bpa.user_project");
        jdbc.update("DELETE FROM bpa.audit_log WHERE table_name = 'user_project'");
        jdbc.update("""
                INSERT INTO bpa.project_job_code (project_ref, job_code_ref, created_by, updated_by) VALUES ('PRJ-142', 'JC-142', 'test', 'test')
                ON CONFLICT (project_ref) DO NOTHING""");
    }

    // --- the scenarios ----------------------------------------------------------------------------------------------------

    @Test
    void BPA76_assignTwoProjectsToUser() {
        TestBrowser ppc = flow.loggedInAs("ppc-team");

        HttpResponse<String> first = assign(ppc, ENGINEER, "PRJ-139");
        HttpResponse<String> second = assign(ppc, ENGINEER, "PRJ-142");

        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        assertThat(second.statusCode()).as(second.body()).isEqualTo(201);
        assertThat(json(second.body()).path("username").asText()).isEqualTo(ENGINEER);
        assertThat(json(second.body()).path("projectRef").asText()).isEqualTo("PRJ-142");
        assertThat(projectsOf(ppc, ENGINEER)).as("project-engineer has 2 projects").containsExactly("PRJ-139", "PRJ-142");
        for (String project : List.of("PRJ-139", "PRJ-142")) {
            String row = jdbc.queryForObject("SELECT id::text FROM bpa.user_project WHERE username = ? AND project_ref = ?", String.class, ENGINEER, project);
            assertThat(audit(row)).as("an audit_log row per business column for the assignment of " + project)
                    .containsExactlyInAnyOrder("username||" + ENGINEER + "|ppc-team", "project_ref||" + project + "|ppc-team");
        }
    }

    @Test
    void BPA76_unassignAProject() {
        TestBrowser ppc = flow.loggedInAs("ppc-team");
        assertThat(assign(ppc, ENGINEER, "PRJ-139").statusCode()).isEqualTo(201);
        assertThat(assign(ppc, ENGINEER, "PRJ-142").statusCode()).isEqualTo(201);
        String row = jdbc.queryForObject("SELECT id::text FROM bpa.user_project WHERE username = ? AND project_ref = 'PRJ-142'", String.class, ENGINEER);

        HttpResponse<String> removed = ppc.delete("/api/v1/users/" + ENGINEER + "/projects/PRJ-142");

        assertThat(removed.statusCode()).as(removed.body()).isEqualTo(204);
        assertThat(projectsOf(ppc, ENGINEER)).as("project-engineer has only PRJ-139").containsExactly("PRJ-139");
        assertThat(audit(row)).as("the removal is audited per business column, new value null")
                .contains("username|" + ENGINEER + "||ppc-team", "project_ref|PRJ-142||ppc-team");
    }

    @Test
    void BPA76_unknownProjectIsRejected() {
        TestBrowser ppc = flow.loggedInAs("ppc-team");

        HttpResponse<String> response = assign(ppc, ENGINEER, "PRJ-999");

        assertRefused(response, 400, "Project PRJ-999 does not exist");
        assertThat(rowCount(ENGINEER)).as("no assignment is saved").isZero();
    }

    @Test
    void BPA76_duplicateAssignmentIsRejected() {
        TestBrowser ppc = flow.loggedInAs("ppc-team");
        assertThat(assign(ppc, ENGINEER, "PRJ-139").statusCode()).isEqualTo(201);

        HttpResponse<String> again = assign(ppc, ENGINEER, "PRJ-139");

        assertRefused(again, 400, "PRJ-139 is already assigned to project-engineer");
        assertThat(rowCount(ENGINEER)).as("still exactly one row").isEqualTo(1);
    }

    @Test
    void BPA76_otherPersonasCannotAssignProjects() {
        TestBrowser supervisor = flow.loggedInAs("supervisor");

        HttpResponse<String> assigned = assign(supervisor, ENGINEER, "PRJ-139");
        HttpResponse<String> listed = supervisor.get("/api/v1/users");

        assertThat(assigned.statusCode()).as(assigned.body()).isEqualTo(403);
        assertThat(listed.statusCode()).as(listed.body()).isEqualTo(403);
        assertThat(rowCount(ENGINEER)).as("nothing is saved").isZero();
    }

    // --- not scenarios (plan BPA-76, Questions and Resolutions) -----------------------------------------------------------------

    /** Every method of every path is ppc-team only, so another group can neither read nor remove (the BPA-10 lesson). */
    @Test
    void otherPersonasCannotReadOrRemoveEither() {
        TestBrowser ppc = flow.loggedInAs("ppc-team");
        assertThat(assign(ppc, ENGINEER, "PRJ-139").statusCode()).isEqualTo(201);
        TestBrowser supervisor = flow.loggedInAs("supervisor");

        assertThat(supervisor.get("/api/v1/projects").statusCode()).isEqualTo(403);
        assertThat(supervisor.get("/api/v1/users/" + ENGINEER + "/projects").statusCode()).isEqualTo(403);
        assertThat(supervisor.delete("/api/v1/users/" + ENGINEER + "/projects/PRJ-139").statusCode()).isEqualTo(403);
        assertThat(rowCount(ENGINEER)).as("the row is still there").isEqualTo(1);
    }

    @Test
    void anUnknownUserIsNotFound() {
        TestBrowser ppc = flow.loggedInAs("ppc-team");

        assertRefused(ppc.get("/api/v1/users/nobody/projects"), 404, "User not found");
        assertRefused(assign(ppc, "nobody", "PRJ-139"), 404, "User not found");
        assertRefused(ppc.delete("/api/v1/users/nobody/projects/PRJ-139"), 404, "User not found");
    }

    /** ASSUMPTION (plan Q2): removing a project the user does not have answers 404 with its own text, so a stale screen says something useful. */
    @Test
    void unassigningAProjectThatIsNotAssignedIsNotFound() {
        TestBrowser ppc = flow.loggedInAs("ppc-team");

        assertRefused(ppc.delete("/api/v1/users/" + ENGINEER + "/projects/PRJ-140"), 404, "PRJ-140 is not assigned to project-engineer");
    }

    /** Resolved Q3 (Ahsan, 24 Nov 2026): no special case, the PPC Team can manage its own projects too. */
    @Test
    void ppcTeamCanManageItsOwnProjects() {
        TestBrowser ppc = flow.loggedInAs("ppc-team");

        HttpResponse<String> response = assign(ppc, "ppc-team", "PRJ-140");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(projectsOf(ppc, "ppc-team")).containsExactly("PRJ-140");
        assertThat(ppc.delete("/api/v1/users/ppc-team/projects/PRJ-140").statusCode()).isEqualTo(204);
    }

    /** Resolved Q1 (Ahsan, 24 Nov 2026): the groups come from Camunda, so the four groups that had no GROUP READ for bpa-service are read as well. */
    @Test
    void theUsersListGivesTheElevenPersonasWithTheirGroups() {
        TestBrowser ppc = flow.loggedInAs("ppc-team");

        HttpResponse<String> response = ppc.get("/api/v1/users");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode users = json(response.body());
        assertThat(users).hasSize(11);
        List<String> names = new ArrayList<>();
        users.forEach(u -> {
            String username = u.path("username").asText();
            names.add(username);
            List<String> groups = new ArrayList<>();
            u.path("groups").forEach(g -> groups.add(g.asText()));
            assertThat(groups).as("groups of " + username + " (a persona's group is its username, docs/04)").containsExactly(username);
            assertThat(u.path("employeeRef").asText()).as("employeeRef of " + username).isNotBlank();
            assertThat(u.path("email").asText()).as("email of " + username).isNotBlank();
        });
        assertThat(names).as("by username, no bpa-service").isSorted().doesNotContain("bpa-service").contains("ppc-team", "project-engineer", "time-method-analyst", "production-planner",
                "supervisor", "production-team");
    }

    @Test
    void theProjectsListGivesTheProjectsThatCanBeAssigned() {
        TestBrowser ppc = flow.loggedInAs("ppc-team");

        HttpResponse<String> response = ppc.get("/api/v1/projects");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        List<String> refs = new ArrayList<>();
        json(response.body()).forEach(p -> refs.add(p.path("projectRef").asText()));
        assertThat(refs).containsExactly("PRJ-139", "PRJ-140", "PRJ-142");
    }

    // --- helpers ----------------------------------------------------------------------------------------------------------

    private HttpResponse<String> assign(TestBrowser browser, String username, String projectRef) {
        return browser.post("/api/v1/users/" + username + "/projects", toJson(Map.of("projectRef", projectRef)));
    }

    private List<String> projectsOf(TestBrowser browser, String username) {
        HttpResponse<String> response = browser.get("/api/v1/users/" + username + "/projects");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        List<String> refs = new ArrayList<>();
        json(response.body()).forEach(p -> refs.add(p.path("projectRef").asText()));
        return refs;
    }

    private int rowCount(String username) {
        return jdbc.queryForObject("SELECT count(*) FROM bpa.user_project WHERE username = ?", Integer.class, username);
    }

    /** The audit rows of one user_project row as "column|old|new|username" (an empty string for a null value). */
    private List<String> audit(String recordId) {
        return jdbc.queryForList("""
                SELECT column_name || '|' || coalesce(old_value, '') || '|' || coalesce(new_value, '') || '|' || username
                FROM bpa.audit_log WHERE table_name = 'user_project' AND record_id = ?::uuid""", String.class, recordId);
    }

    private static void assertRefused(HttpResponse<String> response, int status, String message) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(json(response.body()).path("detail").asText()).isEqualTo(message);
    }
}
