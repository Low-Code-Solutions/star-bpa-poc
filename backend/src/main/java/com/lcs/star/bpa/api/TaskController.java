package com.lcs.star.bpa.api;

import com.lcs.star.bpa.api.generated.TasksApi;
import com.lcs.star.bpa.api.generated.model.CompleteTaskRequest;
import com.lcs.star.bpa.api.generated.model.Task;
import com.lcs.star.bpa.api.generated.model.TaskSummary;
import com.lcs.star.bpa.task.TaskService;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/tasks}: the backend task API (09 S14; BPA-26). The user and groups come from the session (read once at login,
 * S5, S6), never from the request.
 */
@RestController
public class TaskController implements TasksApi {

    private final TaskService tasks;

    public TaskController(TaskService tasks) {
        this.tasks = tasks;
    }

    @Override
    public ResponseEntity<List<TaskSummary>> listTasks() {
        return ResponseEntity.ok(tasks.list(groups(user())));
    }

    @Override
    public ResponseEntity<Task> getTask(String taskKey) {
        return ResponseEntity.ok(tasks.get(taskKey, groups(user())));
    }

    @Override
    public ResponseEntity<Void> completeTask(String taskKey, CompleteTaskRequest request) {
        Authentication user = user();
        tasks.complete(taskKey, user.getName(), groups(user), request.getVariables());
        return ResponseEntity.noContent().build();
    }

    /**
     * A taskKey that breaks the contract's pattern (generated {@code @Pattern}) is a key no task has: 404 "Task not found",
     * the only not-found answer the contract gives these paths (ASSUMPTION; without this it would be a 500).
     */
    @ExceptionHandler({ConstraintViolationException.class, HandlerMethodValidationException.class})
    ResponseEntity<ProblemDetail> malformedKey(Exception e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Task not found"));
    }

    private static Authentication user() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    private static List<String> groups(Authentication user) {
        return user.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }
}
