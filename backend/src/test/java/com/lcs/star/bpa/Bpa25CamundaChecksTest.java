package com.lcs.star.bpa;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.lcs.star.bpa.CamundaRest.Reply;
import com.lcs.star.bpa.adapter.CamundaAdminClient;
import com.lcs.star.bpa.adapter.CamundaIdentityClient;
import com.lcs.star.bpa.config.PersonaFile;
import com.lcs.star.bpa.config.PersonaSync;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * BPA-25 checks 1-5 as JUnit (09 L18), against the integration Camunda 8.10.1 and the real Epic 1 models, as {@code bpa-service}
 * with the permissions BPA-25 recorded (comment 10420). Users and {@code bpa-service} come from the S22 persona sync. The test
 * stands in for the story workers that do not exist yet (save-workpack is BPA-6). Search is eventually consistent, so reads poll.
 */
@Tag("integration")
class Bpa25CamundaChecksTest {

    static final String PROCESS = "Process_PPC_WorkPackAssignment";

    static CamundaRest admin;
    static CamundaRest service;
    static CamundaRest tma;

    @BeforeAll
    static void start() {
        IntegrationCamunda.start();
        String url = IntegrationCamunda.restUrl();
        var adminClient = new CamundaAdminClient(url, IntegrationCamunda.ADMIN, IntegrationCamunda.ADMIN_PASSWORD);
        new PersonaSync(adminClient, IntegrationCamunda.jdbc(), PersonaFile.load(new ClassPathResource("personas.yaml")),
                PersonaSyncTest.DEMO_PASSWORD, PersonaSyncTest.SERVICE_PASSWORD).sync();
        admin = new CamundaRest(url, IntegrationCamunda.ADMIN, IntegrationCamunda.ADMIN_PASSWORD);
        // PersonaSyncTest may have made bpa-service first with another password; the sync keeps it (Q4).
        String servicePassword = new CamundaIdentityClient(url).verify("bpa-service", PersonaSyncTest.SERVICE_PASSWORD).isPresent()
                ? PersonaSyncTest.SERVICE_PASSWORD : PersonaSyncTest.EXISTING_SERVICE_PASSWORD;
        service = new CamundaRest(url, "bpa-service", servicePassword);
        tma = new CamundaRest(url, "tma-1", PersonaSyncTest.DEMO_PASSWORD);

        Reply deployed = service.deploy(
                new ClassPathResource("bpmn/Process_PPC_WorkPackAssignment.bpmn"),
                new ClassPathResource("bpmn/Process_PPC_WorkerReassignment.bpmn"),
                new ClassPathResource("bpmn/Process_PPC_DrawingRevisionNotice.bpmn"),
                new ClassPathResource("dmn/Decision_WorkpackReleaseCheck.dmn"));
        assertThat(deployed.status()).as("bpa-service deploys the models (RESOURCE CREATE, ASSUMPTION): " + deployed.body()).isEqualTo(200);
    }

    @Test
    void BPA25_check1SearchUserTasksByCandidateGroup() {
        String instance = startInstance();

        Reply found = service.pollPost("/v2/user-tasks/search", taskFilter(instance, "time-method-analyst"), r -> items(r).size() == 1);
        assertThat(found.status()).isEqualTo(200);
        assertThat(items(found)).singleElement().satisfies(task -> assertThat(task.path("elementId").asText()).isEqualTo("UserTask_CreateWorkPack"));

        Reply other = service.post("/v2/user-tasks/search", taskFilter(instance, "project-engineer"));
        assertThat(other.status()).isEqualTo(200);
        assertThat(items(other)).isEmpty();
    }

    @Test
    void BPA25_check2UserAssignsThemselvesAndCompletes() {
        // As in the BPA-25 kit: the persona gets only what acting for themselves needs (the sync grants personas nothing).
        var adminClient = new CamundaAdminClient(IntegrationCamunda.restUrl(), IntegrationCamunda.ADMIN, IntegrationCamunda.ADMIN_PASSWORD);
        if (adminClient.authorizations("tma-1").stream().noneMatch(a -> PROCESS.equals(a.resourceId()))) {
            adminClient.createAuthorization("tma-1", "PROCESS_DEFINITION", PROCESS, Set.of("READ_USER_TASK", "UPDATE_USER_TASK", "COMPLETE_USER_TASK"));
        }
        String task = createWorkPackTask(startInstance());

        assertThat(tma.post("/v2/user-tasks/" + task + "/assignment", Map.of("assignee", "tma-1", "allowOverride", true)).status()).isEqualTo(204);
        assertThat(tma.post("/v2/user-tasks/" + task + "/completion", Map.of("variables", Map.of())).status()).isEqualTo(204);

        Reply read = admin.pollGet("/v2/user-tasks/" + task, r -> "COMPLETED".equals(r.body().path("state").asText()));
        assertThat(read.body().path("state").asText()).isEqualTo("COMPLETED");
        assertThat(read.body().path("assignee").asText()).isEqualTo("tma-1");
    }

    @Test
    void BPA25_check3GroupsAreReadAtLogin() {
        assertThat(new CamundaRest(IntegrationCamunda.restUrl(), null, null).get("/v2/topology").status())
                .as("API protected").isEqualTo(401);

        Reply me = tma.get("/v2/authentication/me");
        assertThat(me.status()).isEqualTo(200);
        assertThat(texts(me.body().path("groups"))).contains("time-method-analyst");

        assertThat(new CamundaRest(IntegrationCamunda.restUrl(), "tma-1", "not-the-password").get("/v2/authentication/me").status()).isEqualTo(401);
        assertThat(service.get("/v2/authentication/me").status()).isEqualTo(200);
    }

    @Test
    void BPA25_check4BpmnErrorReturnsTheTaskWithValidationMessages() {
        String instance = startInstance();
        String first = createWorkPackTask(instance);
        completeOnBehalf(first);

        String job = activateSaveWorkPackJob(instance);
        Reply thrown = service.post("/v2/jobs/" + job + "/error", Map.of("errorCode", "WORKPACK_INVALID", "errorMessage", "BPA-43 check 4",
                "variables", Map.of("validationMessages", List.of("Phase is required"))));
        assertThat(thrown.status()).isEqualTo(204);

        Reply back = service.pollPost("/v2/user-tasks/search",
                Map.of("filter", Map.of("processInstanceKey", instance, "state", "CREATED", "elementId", "UserTask_CreateWorkPack")),
                r -> items(r).size() == 1 && !items(r).get(0).path("userTaskKey").asText().equals(first));
        assertThat(items(back)).as("the task came back").hasSize(1);
        String second = items(back).get(0).path("userTaskKey").asText();

        Reply variables = service.pollPost("/v2/user-tasks/" + second + "/variables/search", Map.of(),
                r -> items(r).stream().anyMatch(v -> "validationMessages".equals(v.path("name").asText())));
        assertThat(items(variables)).filteredOn(v -> "validationMessages".equals(v.path("name").asText()))
                .singleElement().satisfies(v -> assertThat(v.path("value").asText()).contains("Phase is required"));
    }

    @Test
    void BPA25_check5BpaServiceCompletesOnBehalfAndCorrelatesAMessage() {
        String instance = startInstance();
        String task = createWorkPackTask(instance);

        completeOnBehalf(task);
        Reply read = admin.pollGet("/v2/user-tasks/" + task, r -> "COMPLETED".equals(r.body().path("state").asText()));
        assertThat(read.body().path("assignee").asText()).isEqualTo("tma-1");

        String job = activateSaveWorkPackJob(instance);
        assertThat(service.post("/v2/jobs/" + job + "/completion", Map.of("variables", Map.of())).status()).isEqualTo(204);

        String key = "BPA43-WP-" + UUID.randomUUID();
        Reply correlated = service.post("/v2/messages/correlation",
                Map.of("name", "AssignmentFileReplaced", "correlationKey", key, "variables", Map.of("workpackNumber", key)));
        assertThat(correlated.status()).as("correlate starts Process_PPC_WorkerReassignment: " + correlated.body()).isEqualTo(200);
        assertThat(correlated.body().path("processInstanceKey").asText()).isNotBlank();
    }

    private static String startInstance() {
        Reply started = service.post("/v2/process-instances", Map.of("processDefinitionId", PROCESS, "variables", Map.of()));
        assertThat(started.status()).as("bpa-service starts the process: " + started.body()).isEqualTo(200);
        return started.body().path("processInstanceKey").asText();
    }

    private static String createWorkPackTask(String instance) {
        Reply found = service.pollPost("/v2/user-tasks/search", taskFilter(instance, "time-method-analyst"), r -> items(r).size() == 1);
        assertThat(items(found)).as("UserTask_CreateWorkPack of " + instance).hasSize(1);
        return items(found).get(0).path("userTaskKey").asText();
    }

    /** S14: bpa-service assigns the task to the real user, then completes it. */
    private static void completeOnBehalf(String task) {
        assertThat(service.post("/v2/user-tasks/" + task + "/assignment", Map.of("assignee", "tma-1", "allowOverride", true)).status()).isEqualTo(204);
        assertThat(service.post("/v2/user-tasks/" + task + "/completion", Map.of("variables", Map.of())).status()).isEqualTo(204);
    }

    private static String activateSaveWorkPackJob(String instance) {
        for (int attempt = 0; attempt < 30; attempt++) {
            Reply activated = service.post("/v2/jobs/activation",
                    Map.of("type", "save-workpack", "worker", "bpa43-test", "timeout", 60000, "maxJobsToActivate", 10, "requestTimeout", 2000));
            for (JsonNode job : activated.body().path("jobs")) {
                if (instance.equals(job.path("processInstanceKey").asText())) {
                    return job.path("jobKey").asText();
                }
            }
        }
        throw new AssertionError("no save-workpack job activated for instance " + instance);
    }

    private static Map<String, Object> taskFilter(String instance, String candidateGroup) {
        return Map.of("filter", Map.of("candidateGroup", candidateGroup, "processInstanceKey", instance, "state", "CREATED"));
    }

    private static List<JsonNode> items(Reply reply) {
        return StreamSupport.stream(reply.body().path("items").spliterator(), false).toList();
    }

    private static List<String> texts(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asText).toList();
    }
}
