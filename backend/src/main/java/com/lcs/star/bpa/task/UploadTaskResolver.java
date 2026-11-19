package com.lcs.star.bpa.task;

import com.lcs.star.bpa.adapter.CamundaTaskClient;
import com.lcs.star.bpa.adapter.CamundaTaskClient.EngineUnavailableException;
import com.lcs.star.bpa.adapter.CamundaTaskClient.UserTask;
import java.util.Collection;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * BPA-59 (plan Q8): the completion report upload names the open Upload completion report task ({@code taskKey}); the task's instance says which week the
 * report belongs to (decision (d): the report has no week column). The task must be an open {@code UserTask_UploadCompletionReport} of the weekly process
 * that the user's group may work, else 404 "Task not found" or 403, as the task API answers (ASSUMPTION texts, as BPA-26).
 */
@Component
public class UploadTaskResolver {

    static final String ELEMENT = "UserTask_UploadCompletionReport";
    static final String PROCESS = "Process_PPC_WeeklyProgressPeriod";

    private final CamundaTaskClient camunda;

    public UploadTaskResolver(CamundaTaskClient camunda) {
        this.camunda = camunda;
    }

    /** The key of the process instance of the open upload task. */
    public long processInstanceOf(String taskKey, Collection<String> groups) {
        UserTask task;
        try {
            task = camunda.get(taskKey).filter(t -> PROCESS.equals(t.processDefinitionId()) && ELEMENT.equals(t.elementId()) && "CREATED".equals(t.state()))
                    .orElseThrow(() -> TaskService.problem(HttpStatus.NOT_FOUND, TaskService.NOT_FOUND));
        } catch (EngineUnavailableException e) {
            throw TaskService.problem(HttpStatus.SERVICE_UNAVAILABLE, TaskService.ENGINE_UNAVAILABLE);
        }
        if (task.candidateGroups().stream().noneMatch(groups::contains)) {
            throw TaskService.problem(HttpStatus.FORBIDDEN, TaskService.FORBIDDEN);
        }
        return Long.parseLong(task.processInstanceKey());
    }
}
