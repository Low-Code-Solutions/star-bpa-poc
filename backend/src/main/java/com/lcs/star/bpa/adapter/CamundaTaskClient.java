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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Camunda 8.10.1 user-task REST calls made as {@code bpa-service} (S25) for the backend task API (S14, ADR 0004). The five
 * calls BPA-25 proved: search by candidate group (check 1), read a task and its variables (checks 2 and 4), assign to the
 * real user and then complete (checks 2 and 5); the variable search adds {@code truncateValues=false} (checked on the local
 * stack, 4 Nov). Search and read go to secondary storage and are eventually consistent.
 */
public class CamundaTaskClient {

    private static final Logger LOG = LoggerFactory.getLogger(CamundaTaskClient.class);

    private static final int PAGE_LIMIT = 100; // POC sizes; ASSUMPTION, no source sizes the task list
    private static final int MAX_TASKS = 2000; // a safety stop for the BPA-73 searches that read every page; ASSUMPTION

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
                .exchange((request, response) -> warnIfCut(ok(response.getStatusCode(), "user task search", read(response.getBody())),
                        "user task search")));
        List<UserTask> tasks = new ArrayList<>();
        body.path("items").forEach(item -> tasks.add(UserTask.of(item)));
        return tasks;
    }

    /**
     * Open (CREATED) tasks of one process whatever their candidate groups (BPA-73: the Work Pack detail lists the open tasks of every group, without task data;
     * the search needs {@code READ_USER_TASK} on the process, which the service user has).
     */
    public List<UserTask> searchOpenAnyGroup(String processDefinitionId) {
        return searchAllPages(Map.of("processDefinitionId", processDefinitionId, "state", "CREATED"));
    }

    /**
     * Open (CREATED) tasks of one process instance, whatever their candidate groups (BPA-73: the Epic 1 and weekly instances of a Work Pack are known by
     * their key, so they are searched by it, not found among a whole process's tasks).
     */
    public List<UserTask> searchOpenOfInstance(String processInstanceKey) {
        return searchAllPages(Map.of("processInstanceKey", processInstanceKey, "state", "CREATED"));
    }

    /** Every page of a user task search (QA review: a search that stops at the first page would silently drop tasks of a busy process). */
    private List<UserTask> searchAllPages(Map<String, Object> filter) {
        List<UserTask> tasks = new ArrayList<>();
        for (int from = 0; from < MAX_TASKS; from += PAGE_LIMIT) {
            int start = from;
            JsonNode body = call(() -> rest.post().uri("/v2/user-tasks/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("filter", filter, "page", Map.of("from", start, "limit", PAGE_LIMIT)))
                    .exchange((request, response) -> ok(response.getStatusCode(), "user task search", read(response.getBody()))));
            body.path("items").forEach(item -> tasks.add(UserTask.of(item)));
            if (tasks.size() >= body.path("page").path("totalItems").asLong(0) || body.path("items").isEmpty()) {
                return tasks;
            }
        }
        LOG.warn("Camunda user task search stopped at {} tasks (MAX_TASKS)", MAX_TASKS);
        return tasks;
    }

    /**
     * The task, or empty when Camunda knows no task with this key (404), or rejects the key itself (400, for example 19 digits
     * above the int64 range, which the contract's pattern lets through).
     */
    public Optional<UserTask> get(String taskKey) {
        return call(() -> rest.get().uri("/v2/user-tasks/{key}", taskKey)
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    if (status == 404 || status == 400) {
                        return Optional.<UserTask>empty();
                    }
                    return Optional.of(UserTask.of(ok(response.getStatusCode(), "user task read", read(response.getBody()))));
                }));
    }

    /**
     * The variables visible to the task, values parsed from Camunda's JSON strings. Where a name exists both on the task's own
     * element instance and on another scope, the task's own value wins; between other scopes the first one returned wins (the
     * four Epic 1 tasks sit in no subprocess, so today the only other scope is the process instance). Values come untruncated
     * ({@code truncateValues=false}): {@code bpa-service} may not read {@code GET /v2/variables/{key}} (403, BPA-26 review).
     */
    public Map<String, Object> variables(UserTask task) {
        JsonNode body = call(() -> rest.post().uri("/v2/user-tasks/{key}/variables/search?truncateValues=false", task.key())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("page", Map.of("limit", PAGE_LIMIT)))
                .exchange((request, response) -> warnIfCut(ok(response.getStatusCode(), "task variable search", read(response.getBody())),
                        "task variable search")));
        Map<String, Object> variables = new LinkedHashMap<>();
        Map<String, Boolean> fromTaskScope = new LinkedHashMap<>();
        for (JsonNode item : body.path("items")) {
            String name = item.path("name").asText();
            boolean local = task.elementInstanceKey() != null && task.elementInstanceKey().equals(item.path("scopeKey").asText());
            if (variables.containsKey(name) && (fromTaskScope.get(name) || !local)) {
                continue;
            }
            variables.put(name, parse(item.path("value").asText()));
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

    /** Searches return one page; say so when Camunda holds more than that page (POC sizes should never reach it). */
    private static JsonNode warnIfCut(JsonNode body, String what) {
        long total = body.path("page").path("totalItems").asLong(0);
        int returned = body.path("items").size();
        if (total > returned) {
            LOG.warn("Camunda {} returned {} of {} items; the rest are not shown (PAGE_LIMIT {})", what, returned, total, PAGE_LIMIT);
        }
        return body;
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
