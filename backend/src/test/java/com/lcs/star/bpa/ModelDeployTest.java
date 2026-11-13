package com.lcs.star.bpa;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.client.CamundaClient;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** BPA-43 Part A (L10, ADR 0014): the models in models/ auto-deploy, and a deploy test covers the DMN. */
@SpringBootTest
@CamundaSpringProcessTest
@ActiveProfiles("test")
@Testcontainers
class ModelDeployTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    CamundaClient client;

    @Test
    void BPA43_modelsDeployToCamunda() {
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Set<String> processes = client.newProcessDefinitionSearchRequest().send().join().items().stream()
                    .map(d -> d.getProcessDefinitionId())
                    .collect(Collectors.toSet());
            assertThat(processes).contains(
                    "Process_PPC_WorkPackAssignment", "Process_PPC_WorkerReassignment", "Process_PPC_DrawingRevisionNotice",
                    "Process_PPC_TimesheetToProgress", "Process_PPC_WeeklyProgressPeriod"); // BPA-48: the two Epic 2 processes
        });
    }

    @Test
    void BPA43_releaseCheckDecisionDeploysAndEvaluates() throws Exception {
        // The three rules of Decision_WorkpackReleaseCheck as written in models/dmn (hit policy FIRST).
        assertThat(evaluate(false, true)).containsEntry("releaseResult", "Not ready")
                .containsEntry("releaseReason", "Materials are not reserved for this Workpack");
        assertThat(evaluate(true, false)).containsEntry("releaseResult", "Not ready")
                .containsEntry("releaseReason", "Drawing is not approved (IFC or As-Built required)");
        assertThat(evaluate(true, true)).containsEntry("releaseResult", "Ready");
    }

    private Map<String, Object> evaluate(boolean materialsReserved, boolean drawingApproved) throws Exception {
        var result = client.newEvaluateDecisionCommand()
                .decisionId("Decision_WorkpackReleaseCheck")
                .variables(Map.of("materialsReserved", materialsReserved, "drawingApproved", drawingApproved))
                .send().join();
        JsonNode output = JSON.readTree(result.getDecisionOutput());
        return JSON.convertValue(output, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
    }
}
