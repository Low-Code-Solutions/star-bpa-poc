package com.lcs.star.bpa.adapter;

import com.lcs.star.bpa.adapter.CamundaTaskClient.EngineUnavailableException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Starts process instances as {@code bpa-service} (S25) through Camunda's REST API, {@code POST /v2/process-instances}, the
 * call BPA-25 proved (check 1 setup and Bpa25CamundaChecksTest). Used by {@code POST /api/v1/workpacks} (BPA-6, D52, S20).
 */
public class CamundaProcessClient {

    private final RestClient rest;

    public CamundaProcessClient(String restUrl, String username, String password) {
        this.rest = RestClient.builder()
                .baseUrl(restUrl)
                .defaultHeaders(h -> h.setBasicAuth(username, password, StandardCharsets.UTF_8))
                .build();
    }

    /** The new instance's key; the process starts with no variables (BPA-46 Q1: the request carries no Work Pack data). */
    public String start(String processDefinitionId) {
        try {
            return rest.post().uri("/v2/process-instances")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("processDefinitionId", processDefinitionId, "variables", Map.of()))
                    .exchange((request, response) -> {
                        if (response.getStatusCode().is5xxServerError()) {
                            throw new EngineUnavailableException("Camunda process start returned " + response.getStatusCode().value());
                        }
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new IllegalStateException("Camunda process start returned " + response.getStatusCode().value()
                                    + ": " + new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
                        }
                        Started started = response.bodyTo(Started.class);
                        return started.processInstanceKey();
                    });
        } catch (ResourceAccessException e) {
            throw new EngineUnavailableException("Camunda cannot be reached: " + e.getMessage());
        }
    }

    /**
     * Correlates a message to the one instance waiting for it, synchronously: {@code POST /v2/messages/correlation} (BPA-12, C3). True
     * when an instance received it; false when none was waiting for this correlation key (Camunda answers 404). Used by the assignment
     * upload for AssignmentFileReceived, where "nothing correlated" must be known at once (D62).
     */
    public boolean correlate(String messageName, String correlationKey, Map<String, Object> variables) {
        try {
            return rest.post().uri("/v2/messages/correlation")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("name", messageName, "correlationKey", correlationKey, "variables", variables))
                    .exchange((request, response) -> {
                        if (response.getStatusCode().is5xxServerError()) {
                            throw new EngineUnavailableException("Camunda message correlation returned " + response.getStatusCode().value());
                        }
                        if (response.getStatusCode().value() == 404) {
                            return false;
                        }
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new IllegalStateException("Camunda message correlation returned " + response.getStatusCode().value()
                                    + ": " + new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
                        }
                        return true;
                    });
        } catch (ResourceAccessException e) {
            throw new EngineUnavailableException("Camunda cannot be reached: " + e.getMessage());
        }
    }

    private record Started(String processInstanceKey) {
    }
}
