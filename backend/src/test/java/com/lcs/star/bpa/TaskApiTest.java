package com.lcs.star.bpa;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcs.star.bpa.CamundaRest.Reply;
import com.lcs.star.bpa.adapter.CamundaAdminClient;
import com.lcs.star.bpa.adapter.CamundaIdentityClient;
import com.lcs.star.bpa.api.TestBrowser;
import com.lcs.star.bpa.config.PersonaFile;
import com.lcs.star.bpa.config.PersonaSync;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BPA-26: the backend task API (09 S14, S6; ADR 0004), one test per Gherkin scenario. The app runs against the integration
 * Camunda 8.10.1 (configured like compose) as {@code bpa-service}; users log in for real through {@code CamundaIdentityClient}.
 * Background: users and groups from personas.yaml (S22 sync); WP-000457 waits at UserTask_SignOffRelease and WP-000460 at
 * UserTask_ConfirmWorkPack. Each test starts its own instances with start instructions, because the workers before those tasks
 * are built by later stories. The task API reads Camunda only, so the Background's project PRJ-139 needs no setup here.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "camunda.client.enabled=false")
@Testcontainers
class TaskApiTest {

    static final String PROCESS = "Process_PPC_WorkPackAssignment";
    static final String SIGN_OFF = "UserTask_SignOffRelease";
    static final String CONFIRM = "UserTask_ConfirmWorkPack";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    static CamundaRest admin;
    static String servicePassword;

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void camunda(DynamicPropertyRegistry registry) {
        background();
        registry.add("camunda.client.rest-address", IntegrationCamunda::restUrl);
        registry.add("bpa.camunda.rest-url", IntegrationCamunda::restUrl);
        registry.add("camunda.client.auth.password", () -> servicePassword);
    }

    /** Camunda, the persona sync and the deployed models; once per JVM is enough, the calls are idempotent. */
    static synchronized void background() {
        if (admin != null) {
            return;
        }
        IntegrationCamunda.start();
        String url = IntegrationCamunda.restUrl();
        var adminClient = new CamundaAdminClient(url, IntegrationCamunda.ADMIN, IntegrationCamunda.ADMIN_PASSWORD);
        new PersonaSync(adminClient, IntegrationCamunda.jdbc(), PersonaFile.load(new ClassPathResource("personas.yaml")),
                PersonaSyncTest.DEMO_PASSWORD, PersonaSyncTest.SERVICE_PASSWORD).sync();
        // PersonaSyncTest may have made bpa-service first with another password; the sync keeps it (as in Bpa25CamundaChecksTest).
        servicePassword = new CamundaIdentityClient(url).verify("bpa-service", PersonaSyncTest.SERVICE_PASSWORD).isPresent()
                ? PersonaSyncTest.SERVICE_PASSWORD : PersonaSyncTest.EXISTING_SERVICE_PASSWORD;
        admin = new CamundaRest(url, IntegrationCamunda.ADMIN, IntegrationCamunda.ADMIN_PASSWORD);
        Reply deployed = admin.deploy(
                new ClassPathResource("bpmn/Process_PPC_WorkPackAssignment.bpmn"),
                new ClassPathResource("bpmn/Process_PPC_WorkerReassignment.bpmn"),
                new ClassPathResource("bpmn/Process_PPC_DrawingRevisionNotice.bpmn"),
                new ClassPathResource("dmn/Decision_WorkpackReleaseCheck.dmn"));
        assertThat(deployed.status()).as("models deploy: " + deployed.body()).isEqualTo(200);
    }

    @Test
    void BPA26_listsOnlyMyTasks() {
        String signOff = taskAt(SIGN_OFF, "WP-000457");
        String confirm = taskAt(CONFIRM, "WP-000460");
        TestBrowser browser = loggedInAs("project-engineer");

        HttpResponse<String> response = browser.get("/api/v1/tasks");

        assertThat(response.statusCode()).isEqualTo(200);
        List<JsonNode> tasks = items(json(response));
        assertThat(tasks).filteredOn(t -> signOff.equals(t.path("key").asText())).singleElement().satisfies(t -> {
            assertThat(t.path("elementId").asText()).isEqualTo(SIGN_OFF);
            assertThat(t.path("workpackNumber").asText()).isEqualTo("WP-000457");
        });
        assertThat(tasks).noneMatch(t -> confirm.equals(t.path("key").asText()));
        assertThat(tasks).allMatch(t -> SIGN_OFF.equals(t.path("elementId").asText()));
    }

    @Test
    void BPA26_getsOneTask() {
        // declineReason is on the task's read allow-list; workpackStatus is not (application.yml, bpa.tasks).
        String signOff = taskAt(SIGN_OFF, Map.of("workpackNumber", "WP-000457", "declineReason", "Wrong drawing revision",
                "workpackStatus", "Draft"));
        TestBrowser browser = loggedInAs("project-engineer");

        HttpResponse<String> response = browser.get("/api/v1/tasks/" + signOff);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode task = json(response);
        assertThat(task.path("key").asText()).isEqualTo(signOff);
        assertThat(task.path("name").asText()).isEqualTo("Sign off release");
        assertThat(task.path("workpackNumber").asText()).isEqualTo("WP-000457");
        assertThat(task.path("variables").isObject()).as("task variables").isTrue();
        assertThat(task.path("variables").path("declineReason").asText()).isEqualTo("Wrong drawing revision");
        assertThat(task.path("variables").has("workpackStatus")).as("not on the read allow-list").isFalse();
        assertThat(task.path("validationMessages").isArray()).isTrue();
        assertThat(task.has("confirmMessage")).as("absent on the sign-off task (contract), not null").isFalse();
    }

    @Test
    void BPA26_completeRecordsRealUser() {
        String signOff = taskAt(SIGN_OFF, "WP-000457");
        TestBrowser browser = loggedInAs("project-engineer");

        HttpResponse<String> response = browser.post("/api/v1/tasks/" + signOff + "/complete",
                "{\"variables\":{\"signOffDecision\":\"Approved\"}}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(204);
        // A completed task cannot be reassigned, so its assignee is the one set before completion. Operate's view is the manual
        // check in the PR (plan Q1: BPA-25 did not confirm what Operate shows when bpa-service completes on a user's behalf).
        JsonNode task = completed(signOff);
        assertThat(task.path("state").asText()).isEqualTo("COMPLETED");
        assertThat(task.path("assignee").asText()).isEqualTo("project-engineer");
    }

    @Test
    void BPA26_wrongGroupIsForbidden() {
        // Examples: | time-method-analyst | not in group project-engineer |
        String signOff = taskAt(SIGN_OFF, "WP-000457");
        TestBrowser browser = loggedInAs("time-method-analyst");

        HttpResponse<String> response = browser.post("/api/v1/tasks/" + signOff + "/complete",
                "{\"variables\":{\"signOffDecision\":\"Approved\"}}");

        assertProblem(response, 403, "You are not allowed to work on this task");
        assertStaysOpen(signOff);
    }

    @Test
    void BPA26_variableNotAllowedIsRejected() {
        String signOff = taskAt(SIGN_OFF, "WP-000457");
        TestBrowser browser = loggedInAs("project-engineer");

        HttpResponse<String> response = browser.post("/api/v1/tasks/" + signOff + "/complete",
                "{\"variables\":{\"signOffDecision\":\"Approved\",\"workpackStatus\":\"Released\"}}");

        assertProblem(response, 400, "Variable workpackStatus is not allowed for this task");
        assertStaysOpen(signOff);
    }

    @Test
    void BPA26_unknownTaskIsNotFound() {
        TestBrowser browser = loggedInAs("project-engineer");

        HttpResponse<String> response = browser.get("/api/v1/tasks/9999999999");

        assertProblem(response, 404, "Task not found");
        // Keys no task can have: outside the contract's pattern, and 19 digits above Camunda's int64 range (BPA-26 review).
        assertProblem(browser.get("/api/v1/tasks/not-a-key"), 404, "Task not found");
        assertProblem(browser.get("/api/v1/tasks/9999999999999999999"), 404, "Task not found");
    }

    @Test
    void BPA26_completedTaskCannotBeCompletedAgain() {
        String signOff = taskAt(SIGN_OFF, "WP-000457");
        TestBrowser browser = loggedInAs("project-engineer");
        String body = "{\"variables\":{\"signOffDecision\":\"Approved\"}}";
        assertThat(browser.post("/api/v1/tasks/" + signOff + "/complete", body).statusCode()).as("first completion").isEqualTo(204);

        // Straight away, while Camunda's search may still read the task as CREATED: the engine refuses (eventual consistency, BPA-25).
        HttpResponse<String> response = browser.post("/api/v1/tasks/" + signOff + "/complete", body);

        assertProblem(response, 409, "Task is already completed");

        // Once the search reads it as COMPLETED: the backend's own state check answers.
        completed(signOff);
        assertProblem(browser.post("/api/v1/tasks/" + signOff + "/complete", body), 409, "Task is already completed");
    }

    private static String taskAt(String elementId, String workpackNumber) {
        return taskAt(elementId, Map.of("workpackNumber", workpackNumber));
    }

    /** Starts an instance at the given user task with these variables and returns the task key once it is searchable. */
    private static String taskAt(String elementId, Map<String, Object> variables) {
        Reply started = admin.post("/v2/process-instances", Map.of(
                "processDefinitionId", PROCESS,
                "variables", variables,
                "startInstructions", List.of(Map.of("elementId", elementId))));
        assertThat(started.status()).as("start at " + elementId + ": " + started.body()).isEqualTo(200);
        String instance = started.body().path("processInstanceKey").asText();
        Reply found = admin.pollPost("/v2/user-tasks/search",
                Map.of("filter", Map.of("processInstanceKey", instance, "elementId", elementId, "state", "CREATED")),
                r -> items(r.body()).size() == 1);
        assertThat(items(found.body())).as(elementId + " of " + instance).hasSize(1);
        return items(found.body()).get(0).path("userTaskKey").asText();
    }

    private TestBrowser loggedInAs(String username) {
        TestBrowser browser = new TestBrowser(port);
        HttpResponse<String> login = browser.login(username, PersonaSyncTest.DEMO_PASSWORD);
        assertThat(login.statusCode()).as("login " + username + ": " + login.body()).isEqualTo(200);
        return browser;
    }

    private static JsonNode completed(String taskKey) {
        return admin.pollGet("/v2/user-tasks/" + taskKey, r -> "COMPLETED".equals(r.body().path("state").asText())).body();
    }

    /**
     * "The task stays open", proved by the engine rather than the eventually consistent search (BPA-26 review): the rightful
     * user can still complete it. The engine refuses a command on a completed task, which the API would answer with 409.
     */
    private void assertStaysOpen(String taskKey) {
        HttpResponse<String> completion = loggedInAs("project-engineer").post("/api/v1/tasks/" + taskKey + "/complete",
                "{\"variables\":{\"signOffDecision\":\"Approved\"}}");
        assertThat(completion.statusCode()).as("still open, so the project engineer can complete it: " + completion.body()).isEqualTo(204);
    }

    private static void assertProblem(HttpResponse<String> response, int status, String detail) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(t -> assertThat(t).startsWith("application/problem+json"));
        assertThat(json(response).path("detail").asText()).isEqualTo(detail);
    }

    private static JsonNode json(HttpResponse<String> response) {
        try {
            return JSON.readTree(response.body());
        } catch (java.io.IOException e) {
            throw new AssertionError("not JSON: " + response.body(), e);
        }
    }

    /** The {@code items} of a Camunda search, or the elements of a JSON array (our list endpoint). */
    private static List<JsonNode> items(JsonNode body) {
        JsonNode array = body.isArray() ? body : body.path("items");
        return StreamSupport.stream(array.spliterator(), false).toList();
    }
}
