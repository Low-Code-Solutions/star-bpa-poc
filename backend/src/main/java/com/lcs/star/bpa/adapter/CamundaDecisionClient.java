package com.lcs.star.bpa.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcs.star.bpa.adapter.CamundaTaskClient.EngineUnavailableException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Evaluates a deployed DMN decision as {@code bpa-service} through Camunda's REST API, {@code POST /v2/decision-definitions/evaluation}
 * (BPA-50: Decision_HoursWithinBudget at every save; the same decision runs in the BPMN at send time). The rules stay in the model; nothing
 * here knows them. The service user needs {@code CREATE_DECISION_INSTANCE} on the decision (PersonaSync; ASSUMPTION, proven by the BPA-50 tests).
 */
public class CamundaDecisionClient {

    private final RestClient rest;
    private final ObjectMapper json;

    public CamundaDecisionClient(String restUrl, String username, String password, ObjectMapper json) {
        this.rest = RestClient.builder()
                .baseUrl(restUrl)
                .defaultHeaders(h -> h.setBasicAuth(username, password, StandardCharsets.UTF_8))
                .build();
        this.json = json;
    }

    /** The decision's output as a JSON object, for a single-hit table (hit policy FIRST). */
    public JsonNode evaluate(String decisionId, Map<String, Object> variables) {
        try {
            return rest.post().uri("/v2/decision-definitions/evaluation")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("decisionDefinitionId", decisionId, "variables", variables))
                    .exchange((request, response) -> {
                        if (response.getStatusCode().is5xxServerError()) {
                            throw new EngineUnavailableException("Camunda decision evaluation returned " + response.getStatusCode().value());
                        }
                        String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new IllegalStateException("Camunda decision evaluation returned " + response.getStatusCode().value() + ": " + body);
                        }
                        JsonNode output = json.readTree(body).path("output");
                        // Camunda answers the output as a JSON text in some versions and as an object in others
                        return output.isTextual() ? json.readTree(output.asText()) : output;
                    });
        } catch (ResourceAccessException e) {
            throw new EngineUnavailableException("Camunda cannot be reached: " + e.getMessage());
        }
    }
}
