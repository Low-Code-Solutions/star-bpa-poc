package com.lcs.star.bpa;

import static com.lcs.star.bpa.CamundaSteps.json;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.lcs.star.bpa.CamundaRest.Reply;
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
 * BPA-78 (E1 portal): the Time &amp; Method Analyst may start a Work Pack only for a project assigned to it. {@code POST /api/v1/workpacks} carries
 * {@code { projectRef }}, ProjectScope (BPA-77) refuses a project the analyst has no user_project row for with 403 before Camunda is called, and the instance
 * carries the project. One test per Gherkin scenario, named as in the plan (Jira gives no names; docs/04); the extras are ours (plan BPA-78). The save step's
 * refusal of an activity of another project is in WorkPackApiTest, beside the helpers that complete the Create task. The Background is built here: the eleven
 * personas as app_user rows, time-method-analyst with PRJ-139 only, and PRJ-142 (not seeded) as a fixture.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkPackProjectScopeApiTest {

    static final String PROCESS = "Process_PPC_WorkPackAssignment";
    static final String CREATE = "UserTask_CreateWorkPack";

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
        jdbc.update("INSERT INTO bpa.project_job_code (project_ref, job_code_ref, created_by, updated_by) VALUES ('PRJ-142', 'JC-142', 'test', 'test') ON CONFLICT (project_ref) DO NOTHING");
        assign("time-method-analyst", "PRJ-139"); // "has PRJ-139 only"
    }

    // --- the scenarios ----------------------------------------------------------------------------------------------------

    /** The picker's source is GET /api/v1/me/projects (BPA-77); the React twin checks the picker itself. Passes before BPA-78's code: BPA-77 built the endpoint. */
    @Test
    void BPA78_pickerListsOnlyAssignedProjects() {
        TestBrowser analyst = flow.loggedInAs("time-method-analyst");

        HttpResponse<String> response = analyst.get("/api/v1/me/projects");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(refs(json(response.body()))).as("the picker offers PRJ-139 only").containsExactly("PRJ-139");
    }

    @Test
    void BPA78_apiRejectsUnassignedProject() {
        TestBrowser analyst = flow.loggedInAs("time-method-analyst");
        int before = instances();

        HttpResponse<String> response = analyst.post("/api/v1/workpacks", "{\"projectRef\":\"PRJ-142\"}");

        assertRefused(response, 403, "Project PRJ-142 is not assigned to you");
        assertThat(instances()).as("no process instance of Process_PPC_WorkPackAssignment starts").isEqualTo(before);
    }

    // --- not scenarios (plan BPA-78) -----------------------------------------------------------------------------------------

    @Test
    void startWithAnAssignedProjectCarriesIt() {
        TestBrowser analyst = flow.loggedInAs("time-method-analyst");

        HttpResponse<String> response = analyst.post("/api/v1/workpacks", "{\"projectRef\":\"PRJ-139\"}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        long instance = Long.parseLong(json(response.body()).path("processInstanceKey").asText());
        // Camunda's variable search is eventually consistent (BPA-25), so wait for it
        String carried = CamundaSteps.poll(() -> CamundaSteps.variable(instance, "projectRef"), v -> v != null, "projectRef of instance " + instance);
        assertThat(carried).as("the instance carries the project").contains("PRJ-139");
        String task = CamundaSteps.awaitOpenTask(instance, CREATE).path("userTaskKey").asText();
        JsonNode shown = json(analyst.get("/api/v1/tasks/" + task).body());
        assertThat(shown.path("variables").path("projectRef").asText()).as("the Create Work Pack task shows the project (read allow-list)").isEqualTo("PRJ-139");
    }

    /** ASSUMPTION (plan): a missing body, an empty object and a blank reference are all "Project is required"; no instance starts. */
    @Test
    void startWithoutAProjectIsRefused() {
        TestBrowser analyst = flow.loggedInAs("time-method-analyst");
        int before = instances();

        assertRefused(analyst.post("/api/v1/workpacks", ""), 400, "Project is required");
        assertRefused(analyst.post("/api/v1/workpacks", "{}"), 400, "Project is required");
        assertRefused(analyst.post("/api/v1/workpacks", "{\"projectRef\":\"  \"}"), 400, "Project is required");
        assertThat(instances()).as("no instance started").isEqualTo(before);
    }

    /** An unknown project has no user_project row, so it is refused like an unassigned one (the answer does not say whether a project exists). */
    @Test
    void anUnknownProjectIsRefusedLikeAnUnassignedOne() {
        TestBrowser analyst = flow.loggedInAs("time-method-analyst");

        assertRefused(analyst.post("/api/v1/workpacks", "{\"projectRef\":\"PRJ-999\"}"), 403, "Project PRJ-999 is not assigned to you");
    }

    /** The group gate comes first: another group is refused for its group, whatever it sends, and nothing starts. */
    @Test
    void otherGroupsAreStillRefusedForTheirGroup() {
        assign("production-team", "PRJ-139");
        TestBrowser productionTeam = flow.loggedInAs("production-team");
        int before = instances();

        HttpResponse<String> response = productionTeam.post("/api/v1/workpacks", "{\"projectRef\":\"PRJ-139\"}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
        assertThat(json(response.body()).path("detail").asText()).as("refused for the group, not for the project").contains("groups do not allow");
        assertThat(instances()).isEqualTo(before);
    }

    /** One rule for every persona: an analyst with two projects may start for either; a project assigned later is allowed from then on. */
    @Test
    void anAnalystWithSeveralProjectsMayStartForEither() {
        assign("time-method-analyst", "PRJ-142");
        TestBrowser analyst = flow.loggedInAs("time-method-analyst");

        assertThat(analyst.post("/api/v1/workpacks", "{\"projectRef\":\"PRJ-139\"}").statusCode()).isEqualTo(201);
        assertThat(analyst.post("/api/v1/workpacks", "{\"projectRef\":\"PRJ-142\"}").statusCode()).isEqualTo(201);
    }

    // --- helpers ----------------------------------------------------------------------------------------------------------

    private void assign(String username, String projectRef) {
        jdbc.update("INSERT INTO bpa.user_project (username, project_ref, created_by, updated_by) VALUES (?, ?, 'test', 'test') ON CONFLICT (username, project_ref) DO NOTHING",
                username, projectRef);
    }

    /** How many instances of Process_PPC_WorkPackAssignment Camunda holds, in any state. */
    private static int instances() {
        Reply found = IntegrationApp.admin.post("/v2/process-instances/search",
                Map.of("filter", Map.of("processDefinitionId", PROCESS), "page", Map.of("limit", 1)));
        return found.body().path("page").path("totalItems").asInt();
    }

    private static List<String> refs(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(e -> values.add(e.path("projectRef").asText()));
        return values;
    }

    private static void assertRefused(HttpResponse<String> response, int status, String message) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(json(response.body()).path("detail").asText()).isEqualTo(message);
    }
}
