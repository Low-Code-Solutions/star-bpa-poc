package com.lcs.star.bpa.task;

import com.lcs.star.bpa.adapter.CamundaTaskClient;
import com.lcs.star.bpa.adapter.CamundaTaskClient.UserTask;
import com.lcs.star.bpa.api.generated.model.OpenTask;
import com.lcs.star.bpa.persistence.WorkPackDetailRepository;
import com.lcs.star.bpa.persistence.WorkPackDetailRepository.Header;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * The open user tasks on one Work Pack, from every group (BPA-73; the "Open tasks" side panel), with no task data: name, element, the groups the task is for,
 * when it was created, and whether the user's own groups include one of them. Read by the service user (the task API is limited to the user's groups, so
 * this is a separate, read-only read). Which tasks belong to the Work Pack (plan BPA-73 Q1, ASSUMPTION for the last):
 * the Epic 1 instance's (by instance key), the timesheet process's (by the {@code workpackNumber} variable) and the weekly process's of a week that is not
 * Locked and in which the Work Pack has a progress row. Only allow-listed elements are listed, as the task list does. Opening a task stays with
 * {@code GET /api/v1/tasks/{key}}, which answers 403 outside the task's groups.
 */
@Service
public class WorkPackOpenTaskService {

    static final String EPIC1 = "Process_PPC_WorkPackAssignment";
    static final String TIMESHEET = "Process_PPC_TimesheetToProgress";
    static final String WEEKLY = "Process_PPC_WeeklyProgressPeriod";

    private final CamundaTaskClient camunda;
    private final TaskAllowList allowList;
    private final WorkPackDetailRepository detail;

    public WorkPackOpenTaskService(CamundaTaskClient camunda, TaskAllowList allowList, WorkPackDetailRepository detail) {
        this.camunda = camunda;
        this.allowList = allowList;
        this.detail = detail;
    }

    /** @throws CamundaTaskClient.EngineUnavailableException when the process engine cannot be reached (the caller answers 503) */
    public List<OpenTask> list(String workpackNumber, Header header, Collection<String> userGroups) {
        Set<String> weekly = Set.copyOf(detail.openWeeklyInstanceKeys(header.id()));
        String epic1 = Long.toString(header.processInstanceKey());
        return allowList.processIds().stream().flatMap(process -> camunda.searchOpenAnyGroup(process).stream())
                .filter(t -> allowList.forElement(t.elementId()).isPresent())
                .filter(t -> belongs(t, workpackNumber, epic1, weekly))
                .sorted(Comparator.comparing(UserTask::creationDate, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(t -> new OpenTask(t.key(), t.name(), t.elementId(), t.candidateGroups(), t.creationDate(), t.candidateGroups().stream().anyMatch(userGroups::contains)))
                .toList();
    }

    private boolean belongs(UserTask task, String workpackNumber, String epic1Instance, Set<String> weeklyInstances) {
        return switch (task.processDefinitionId()) {
            case EPIC1 -> epic1Instance.equals(task.processInstanceKey());
            case WEEKLY -> weeklyInstances.contains(task.processInstanceKey());
            case TIMESHEET -> Objects.equals(workpackNumber, camunda.variables(task).get("workpackNumber"));
            default -> false;
        };
    }
}
