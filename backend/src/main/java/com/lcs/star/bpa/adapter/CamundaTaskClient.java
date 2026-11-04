package com.lcs.star.bpa.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Camunda 8.10.1 user-task REST calls made as {@code bpa-service} (S25) for the backend task API (S14, ADR 0004). Only the
 * calls BPA-25 proved: search by candidate group (check 1), read a task and its variables (checks 2 and 4), assign to the
 * real user and then complete (checks 2 and 5). Search and read go to secondary storage and are eventually consistent.
 */
public class CamundaTaskClient {

    private static final int PAGE_LIMIT = 100; // POC sizes; ASSUMPTION, no source sizes the task list

    private final RestClient rest;
    private final ObjectMapper json;

    public CamundaTaskClient(String restUrl, String username, String password, ObjectMapper json) {
        this.rest = RestClient.builder()
                .baseUrl(restUrl)
                .defaultHeaders(h -> h.setBasicAuth(username, password, StandardCharsets.UTF_8))
                .build();
        this.json = json;
    }

    /** Open (CREATED) tasks of one process whose candidate groups include {@code candidateGroup}. */
    public List<UserTask> searchOpen(String candidateGroup, String processDefinitionId) {
        JsonNode body = call(() -> rest.post().uri("/v2/user-tasks/search")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "filter", Map.of("candidateGroup", candidateGroup, "processDefinitionId", processDefinitionId, "state", "CREATED"),
                        "page", Map.of("limit", PAGE_LIMIT)))
                .exchange((request, response) -> ok(response.getStatusCode(), "user task search", read(response.getBody()))));
        List<UserTask> tasks = new ArrayList<>();
        body.path("items").forEach(item -> tasks.add(UserTask.of(item)));
        return tasks;
    }

    /** The task, or empty when Camunda knows no task with this key (404). */
    public Optional<UserTask> get(String taskKey) {
        return call(() -> rest.get().uri("/v2/user-tasks/{key}", taskKey)
                .exchange((request, response) -> {
                    if (response.getStatusCode().value() == 404) {
                        return Optional.<UserTask>empty();
                    }
                    return Optional.of(UserTask.of(ok(response.getStatusCode(), "user task read", read(response.getBody()))));
                }));
    }

    /**
     * The variables visible to the task, values parsed from Camunda's JSON strings. Where a name exists in several scopes, the
     * innermost (the task's own element instance) wins. Truncated values are read in full.
     */
    public Map<String, Object> variables(UserTask task) {
        JsonNode body = call(() -> rest.post().uri("/v2/user-tasks/{key}/variables/search", task.key())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("page", Map.of("limit", PAGE_LIMIT)))
                .exchange((request, response) -> ok(response.getStatusCode(), "task variable search", read(response.getBody()))));
        Map<String, Object> variables = new LinkedHashMap<>();
        Map<String, Boolean> fromTaskScope = new LinkedHashMap<>();
        for (JsonNode item : body.path("items")) {
            String name = item.path("name").asText();
            boolean local = task.elementInstanceKey() != null && task.elementInstanceKey().equals(item.path("scopeKey").asText());
            if (variables.containsKey(name) && (fromTaskScope.get(name) || !local)) {
                continue;
            }
            String value = item.path("isTruncated").asBoolean() ? fullValue(item.path("variableKey").asText()) : item.path("value").asText();
            variables.put(name, parse(value));
            fromTaskScope.put(name, local);
        }
        return variables;
    }

    /** Assigns the task to the real user (S14), overriding any earlier assignee. */
    public void assign(String taskKey, String assignee) {
        command("/v2/user-tasks/{key}/assignment", taskKey, Map.of("assignee", assignee, "allowOverride", true));
    }

    public void complete(String taskKey, Map<String, Object> variables) {
        command("/v2/user-tasks/{key}/completion", taskKey, Map.of("variables", variables));
    }

    private void command(String path, String taskKey, Map<String, Object> body) {
        call(() -> rest.post().uri(path, taskKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    // The engine rejects a command on a task that is completed or gone with 404 or 409, even while the search
                    // still reads it as CREATED (BPA-26 plan, Risks).
                    if (status == 404 || status == 409) {
                        throw new TaskGoneException(path + " rejected with " + status + ": " + read(response.getBody()));
                    }
                    ok(response.getStatusCode(), path, read(response.getBody()));
                    return null;
                }));
    }

    private String fullValue(String variableKey) {
        JsonNode body = call(() -> rest.get().uri("/v2/variables/{key}", variableKey)
                .exchange((request, response) -> ok(response.getStatusCode(), "variable read", read(response.getBody()))));
        return body.path("value").asText();
    }

    private Object parse(String value) {
        try {
            return json.readValue(value, Object.class);
        } catch (JsonProcessingException e) {
            return value;
        }
    }

    private JsonNode read(java.io.InputStream body) throws java.io.IOException {
        byte[] bytes = body.readAllBytes();
        return bytes.length == 0 ? json.nullNode() : json.readTree(bytes);
    }

    private static JsonNode ok(HttpStatusCode status, String what, JsonNode body) {
        if (status.is5xxServerError()) {
            throw new EngineUnavailableException("Camunda " + what + " returned " + status.value() + ": " + body);
        }
        if (!status.is2xxSuccessful()) {
            throw new IllegalStateException("Camunda " + what + " returned " + status.value() + ": " + body);
        }
        return body;
    }

    private static <T> T call(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (ResourceAccessException e) {
            throw new EngineUnavailableException("Camunda cannot be reached: " + e.getMessage());
        }
    }

    /** The fields of a Camunda user task that the task API uses. */
    public record UserTask(String key, String name, String elementId, String elementInstanceKey, String processDefinitionId,
            String processInstanceKey, String state, List<String> candidateGroups, OffsetDateTime creationDate) {

        static UserTask of(JsonNode item) {
            List<String> groups = new ArrayList<>();
            item.path("candidateGroups").forEach(g -> groups.add(g.asText()));
            String created = item.path("creationDate").asText(null);
            return new UserTask(item.path("userTaskKey").asText(), item.path("name").asText(), item.path("elementId").asText(),
                    item.path("elementInstanceKey").asText(null), item.path("processDefinitionId").asText(),
                    item.path("processInstanceKey").asText(),
                    item.path("state").asText(), List.copyOf(groups), created == null ? null : OffsetDateTime.parse(created));
        }
    }

    /** Camunda refused a command because the task is no longer open. */
    public static class TaskGoneException extends RuntimeException {
        public TaskGoneException(String message) {
            super(message);
        }
    }

    /** Camunda cannot be reached or failed (5xx); nothing is implied about the task. */
    public static class EngineUnavailableException extends RuntimeException {
        public EngineUnavailableException(String message) {
            super(message);
        }
    }
}
