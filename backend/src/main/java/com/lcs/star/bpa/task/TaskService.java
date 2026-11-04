package com.lcs.star.bpa.task;

import com.lcs.star.bpa.adapter.CamundaTaskClient;
import com.lcs.star.bpa.adapter.CamundaTaskClient.EngineUnavailableException;
import com.lcs.star.bpa.adapter.CamundaTaskClient.TaskGoneException;
import com.lcs.star.bpa.adapter.CamundaTaskClient.UserTask;
import com.lcs.star.bpa.api.generated.model.Task;
import com.lcs.star.bpa.api.generated.model.TaskSummary;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * The backend task API rules (09 S14, S6; ADR 0004; BPA-26). Access is by Camunda group only (BPA-44). Checks run in the order
 * 404, 403, 409, 400 (ASSUMPTION: no source orders them). A completion assigns the task to the session user and then completes
 * it, so Camunda records the real user. Validity conditions are added by the stories that state them (BPA-9 first).
 */
public class TaskService {

    private static final Logger LOG = LoggerFactory.getLogger(TaskService.class);

    static final String NOT_FOUND = "Task not found";
    static final String FORBIDDEN = "You are not allowed to work on this task";
    static final String ALREADY_COMPLETED = "Task is already completed";
    static final String ENGINE_UNAVAILABLE = "The process engine is not available right now; try again later";

    private final CamundaTaskClient camunda;
    private final TaskAllowList allowList;

    public TaskService(CamundaTaskClient camunda, TaskAllowList allowList) {
        this.camunda = camunda;
        this.allowList = allowList;
    }

    /** Open tasks whose candidate groups include one of the user's groups, oldest first. */
    public List<TaskSummary> list(Collection<String> groups) {
        return engine(() -> {
            Map<String, UserTask> tasks = new LinkedHashMap<>();
            for (String group : groups.stream().sorted().toList()) {
                camunda.searchOpen(group, allowList.processId()).stream()
                        .filter(t -> allowList.forElement(t.elementId()).isPresent())
                        .forEach(t -> tasks.putIfAbsent(t.key(), t));
            }
            List<TaskSummary> summaries = new ArrayList<>();
            for (UserTask task : tasks.values()) {
                TaskSummary summary = new TaskSummary(task.key(), task.name(), TaskSummary.ElementIdEnum.fromValue(task.elementId()),
                        task.processInstanceKey(), task.creationDate());
                summary.setWorkpackNumber(text(camunda.variables(task).get("workpackNumber")));
                summaries.add(summary);
            }
            summaries.sort(Comparator.comparing(TaskSummary::getCreationDate, Comparator.nullsLast(Comparator.naturalOrder())));
            return summaries;
        });
    }

    /** One task with its read-allow-listed variables, validationMessages and confirmMessage. */
    public Task get(String taskKey, Collection<String> groups) {
        return engine(() -> {
            UserTask task = served(taskKey);
            requireCandidate(task, groups);
            Map<String, Object> all = camunda.variables(task);
            Map<String, Object> visible = new LinkedHashMap<>();
            for (String name : allowList.forElement(task.elementId()).orElseThrow().read()) {
                if (all.containsKey(name)) {
                    visible.put(name, all.get(name));
                }
            }
            Task result = new Task(task.key(), task.name(), Task.ElementIdEnum.fromValue(task.elementId()), task.processInstanceKey(),
                    task.creationDate(), visible, messages(all.get("validationMessages")));
            result.setWorkpackNumber(text(all.get("workpackNumber")));
            result.setConfirmMessage(text(all.get("confirmMessage")));
            return result;
        });
    }

    /** Checks group, state and the write allow-list, then assigns the task to {@code username} and completes it. */
    public void complete(String taskKey, String username, Collection<String> groups, Map<String, Object> variables) {
        Map<String, Object> submitted = variables == null ? Map.of() : variables;
        engine(() -> {
            UserTask task = served(taskKey);
            requireCandidate(task, groups);
            switch (task.state()) {
                case "CREATED" -> {
                }
                case "COMPLETED", "COMPLETING" -> throw problem(HttpStatus.CONFLICT, ALREADY_COMPLETED);
                // Cancelled with its instance, or failed: no longer open (ASSUMPTION, no source states it).
                default -> throw problem(HttpStatus.NOT_FOUND, NOT_FOUND);
            }
            var writable = allowList.forElement(task.elementId()).orElseThrow().writable();
            submitted.keySet().stream().sorted().filter(name -> !writable.contains(name)).findFirst().ifPresent(name -> {
                throw problem(HttpStatus.BAD_REQUEST, "Variable " + name + " is not allowed for this task");
            });
            try {
                camunda.assign(task.key(), username);
                camunda.complete(task.key(), submitted);
            } catch (TaskGoneException e) {
                // The search still read the task as open, but the engine has already completed it (eventual consistency).
                LOG.info("Completion of task {} by {} refused by Camunda: {}", taskKey, username, e.getMessage());
                throw problem(HttpStatus.CONFLICT, ALREADY_COMPLETED);
            }
            return null;
        });
    }

    /** The task, if Camunda knows it and it is one of the user tasks this API serves. */
    private UserTask served(String taskKey) {
        return camunda.get(taskKey)
                .filter(t -> allowList.processId().equals(t.processDefinitionId()))
                .filter(t -> allowList.forElement(t.elementId()).isPresent())
                .orElseThrow(() -> problem(HttpStatus.NOT_FOUND, NOT_FOUND));
    }

    private static void requireCandidate(UserTask task, Collection<String> groups) {
        if (task.candidateGroups().stream().noneMatch(groups::contains)) {
            throw problem(HttpStatus.FORBIDDEN, FORBIDDEN);
        }
    }

    private static <T> T engine(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (EngineUnavailableException e) {
            LOG.warn("Task API call failed: {}", e.getMessage());
            throw problem(HttpStatus.SERVICE_UNAVAILABLE, ENGINE_UNAVAILABLE);
        }
    }

    private static List<String> messages(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return value == null ? List.of() : List.of(String.valueOf(value));
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    static ErrorResponseException problem(HttpStatus status, String detail) {
        return new ErrorResponseException(status, ProblemDetail.forStatusAndDetail(status, detail), null);
    }
}
