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
        return start(processDefinitionId, Map.of());
    }

    /** The new instance's key, the process started with these variables (BPA-78: the project the request is for, as {@code projectRef}). */
    public String start(String processDefinitionId, Map<String, Object> variables) {
        try {
            return rest.post().uri("/v2/process-instances")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("processDefinitionId", processDefinitionId, "variables", variables))
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

    /**
     * Publishes a message with time-to-live 0 and a message id: {@code POST /v2/messages/publication} (BPA-49). Unlike {@link #correlate} it does not
     * say whether anything received it: a message start event that already has an active instance with this correlation key drops it silently, which is
     * what a retried publish needs. Only a 5xx or an unreachable engine is an error.
     */
    public void publish(String messageName, String correlationKey, String messageId, Map<String, Object> variables) {
        publish(messageName, correlationKey, messageId, variables, 0);
    }

    /**
     * The same publication with a time-to-live in milliseconds (BPA-67: WorkPackCompleted waits, buffered, while a Work Pack's hours are in approval, P7D = 604800000, ASSUMPTION;
     * the message id then stops a duplicate while it is buffered, ADR 0007). 0 is BPA-49's.
     */
    public void publish(String messageName, String correlationKey, String messageId, Map<String, Object> variables, long timeToLiveMillis) {
        try {
            rest.post().uri("/v2/messages/publication")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("name", messageName, "correlationKey", correlationKey, "messageId", messageId, "timeToLive", timeToLiveMillis, "variables", variables))
                    .exchange((request, response) -> {
                        if (response.getStatusCode().is5xxServerError()) {
                            throw new EngineUnavailableException("Camunda message publication returned " + response.getStatusCode().value());
                        }
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                            // BPA-67: a message with this id is still buffered (a time to live above 0): the same publish again is a duplicate, which stays dropped (ADR 0007)
                            if (response.getStatusCode().value() == 409 && body.contains("ALREADY_EXISTS")) {
                                return null;
                            }
                            throw new IllegalStateException("Camunda message publication returned " + response.getStatusCode().value() + ": " + body);
                        }
                        return null;
                    });
        } catch (ResourceAccessException e) {
            throw new EngineUnavailableException("Camunda cannot be reached: " + e.getMessage());
        }
    }

    private record Started(String processInstanceKey) {
    }
}
