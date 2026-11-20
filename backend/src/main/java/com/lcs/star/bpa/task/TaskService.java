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

    /** Process variable set on every completion: the Camunda username of the user who completed the task (D63). */
    public static final String COMPLETED_BY = "completedBy";

    /** Process variable set on every completion: the key of the user task that was completed (BPA-9, the sign-off's re-run key). */
    public static final String COMPLETED_TASK_KEY = "completedTaskKey";

    static final String NOT_FOUND = "Task not found";
    static final String FORBIDDEN = "You are not allowed to work on this task";
    static final String ALREADY_COMPLETED = "Task is already completed";
    static final String ENGINE_UNAVAILABLE = "The process engine is not available right now; try again later";

    private final CamundaTaskClient camunda;
    private final TaskAllowList allowList;
    private final HoursSegregationCheck segregation;
    private final FmsHandOffCheck fmsHandOff;
    private final CompletionReportCheck completionReport;
    private final CompletionApprovalCheck completionApproval;

    public TaskService(CamundaTaskClient camunda, TaskAllowList allowList, HoursSegregationCheck segregation, FmsHandOffCheck fmsHandOff,
            CompletionReportCheck completionReport, CompletionApprovalCheck completionApproval) {
        this.camunda = camunda;
        this.allowList = allowList;
        this.segregation = segregation;
        this.fmsHandOff = fmsHandOff;
        this.completionReport = completionReport;
        this.completionApproval = completionApproval;
    }

    /** Open tasks whose candidate groups include one of the user's groups, oldest first. */
    public List<TaskSummary> list(Collection<String> groups) {
        return engine(() -> {
            Map<String, UserTask> tasks = new LinkedHashMap<>();
            for (String group : groups.stream().sorted().toList()) {
                for (String process : allowList.processIds()) {
                    camunda.searchOpen(group, process).stream()
                            .filter(t -> allowList.forElement(t.elementId()).isPresent())
                            .forEach(t -> tasks.putIfAbsent(t.key(), t));
                }
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
            List<String> shown = new ArrayList<>(messages(all.get("validationMessages")));
            if ("UserTask_BookHours".equals(task.elementId())) {
                budgetMessages(all.get("lineCheckResults")).stream().filter(m -> !shown.contains(m)).forEach(shown::add);
            }
            Task result = new Task(task.key(), task.name(), Task.ElementIdEnum.fromValue(task.elementId()), task.processInstanceKey(),
                    task.creationDate(), visible, shown);
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
                // Open, including the short-lived states while a task listener runs (none in the model today); the engine
                // has the last word on those through the assign and complete below.
                case "CREATED", "CREATING", "ASSIGNING", "UPDATING" -> {
                }
                case "COMPLETED", "COMPLETING" -> throw problem(HttpStatus.CONFLICT, ALREADY_COMPLETED);
                // Cancelled with its instance, or failed: no longer open (ASSUMPTION, decided by Ahsan 4 Nov: 404).
                default -> throw problem(HttpStatus.NOT_FOUND, NOT_FOUND);
            }
            var writable = allowList.forElement(task.elementId()).orElseThrow().writable();
            submitted.keySet().stream().sorted().filter(name -> !writable.contains(name)).findFirst().ifPresent(name -> {
                throw problem(HttpStatus.BAD_REQUEST, "Variable " + name + " is not allowed for this task");
            });
            // BPA-9: the rules that affect validity are re-checked here before the task is assigned or completed, so a refused
            // completion leaves the task open (09 S14; the React screen checks the same rules).
            TaskValidity.check(task.elementId(), submitted).ifPresent(message -> {
                throw problem(HttpStatus.BAD_REQUEST, message);
            });
            // BPA-56, BPA-57: segregation of duties for the hours approvals (ASSUMPTION status and text, plan Q1): 403 for the sender, 409 for an earlier decider at PPC; the task stays open
            if (segregation.appliesTo(task.elementId())) {
                segregation.refusal(task.elementId(), camunda.variables(task), username).ifPresent(refusal -> {
                    throw problem(refusal.status(), refusal.message());
                });
            }
            // BPA-58: the Send hours to FMS completion names every file of the task and no other (ASSUMPTION texts); the task stays open
            if (fmsHandOff.appliesTo(task.elementId())) {
                fmsHandOff.refusal(camunda.variables(task), submitted).ifPresent(message -> {
                    throw problem(HttpStatus.BAD_REQUEST, message);
                });
            }
            // BPA-59: the report must be one of this task's own week (ASSUMPTION text); the task stays open
            if (completionReport.appliesTo(task.elementId())) {
                completionReport.refusal(Long.parseLong(task.processInstanceKey()), submitted).ifPresent(message -> {
                    throw problem(HttpStatus.BAD_REQUEST, message);
                });
            }
            // BPA-60: the week before must be locked (409) and the approvals must be complete and valid (400); the task stays open
            if (completionApproval.appliesTo(task.elementId())) {
                completionApproval.refusal(Long.parseLong(task.processInstanceKey()), submitted).ifPresent(refusal -> {
                    throw problem(refusal.status(), refusal.message());
                });
            }
            // D63 (BPA-6 plan, Ahsan 4 Nov): the save workers record who completed the task. Set here from the session, never
            // from the request: no allow-list holds "completedBy", so a client that sends it gets the 400 above.
            // BPA-9 (Ahsan, 5 Nov): likewise the key of the task being completed, which a job worker needs as its re-run key
            // (D65): a job carries only its own key. Never from the request either.
            Map<String, Object> completion = new LinkedHashMap<>(submitted);
            completion.put(COMPLETED_BY, username);
            completion.put(COMPLETED_TASK_KEY, task.key());
            try {
                camunda.assign(task.key(), username);
                camunda.complete(task.key(), completion);
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
                .filter(t -> allowList.processIds().contains(t.processDefinitionId()))
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

    /**
     * BPA-51: the send-time budget re-check returns the instance to Book hours with lineCheckResults, one element per line, each with a
     * {@code message} for a Blocked reason (BusinessRuleTask_CheckHoursWithinBudget's output). Those texts are shown as validationMessages, once each.
     */
    private static List<String> budgetMessages(Object lineCheckResults) {
        List<String> found = new ArrayList<>();
        if (lineCheckResults instanceof List<?> results) {
            for (Object result : results) {
                if (result instanceof Map<?, ?> entry && "Blocked".equals(entry.get("budgetResult")) && entry.get("message") != null
                        && !found.contains(String.valueOf(entry.get("message")))) {
                    found.add(String.valueOf(entry.get("message")));
                }
            }
        }
        return found;
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
