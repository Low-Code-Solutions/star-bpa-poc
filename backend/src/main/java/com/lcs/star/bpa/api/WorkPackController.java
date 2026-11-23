package com.lcs.star.bpa.api;

import com.lcs.star.bpa.adapter.CamundaProcessClient;
import com.lcs.star.bpa.adapter.CamundaTaskClient.EngineUnavailableException;
import com.lcs.star.bpa.api.generated.WorkpacksApi;
import com.lcs.star.bpa.persistence.WorkPackListRepository;
import com.lcs.star.bpa.api.generated.model.StartWorkPackResponse;
import com.lcs.star.bpa.api.generated.model.WorkPackListRow;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/workpacks}: the Time &amp; Method Analyst starts a Work Pack request (BPA-6; D52, 09 S20). SecurityConfig
 * lets only {@code time-method-analyst} through (403 otherwise). The call carries no body (BPA-46 Q1): the Work Pack data is
 * entered in the Create Work Pack task that follows, through the task API.
 * {@code GET /api/v1/workpacks} (BPA-72) is the read-only Work Packs list for every logged-in group.
 */
@RestController
public class WorkPackController implements WorkpacksApi {

    static final String PROCESS = "Process_PPC_WorkPackAssignment";

    private static final Logger LOG = LoggerFactory.getLogger(WorkPackController.class);

    static final List<String> STATUSES = List.of("Draft", "Released", "In Progress", "Completed");

    private final CamundaProcessClient camunda;
    private final WorkPackListRepository list;

    public WorkPackController(CamundaProcessClient camunda, WorkPackListRepository list) {
        this.camunda = camunda;
        this.list = list;
    }


    /**
     * {@code GET /api/v1/workpacks} (BPA-72): the Work Packs list, read only, for every logged-in user (SecurityConfig: authenticated). Not limited to the
     * user's projects until BPA-79 (plan BPA-72 Q1). A status that is not one of the four is a 400; no match is a 200 with an empty list.
     */
    @Override
    public ResponseEntity<List<WorkPackListRow>> listWorkPacks(List<String> status, String project, LocalDate weekEnding, String search) {
        if (status != null) {
            for (String one : status) {
                if (!STATUSES.contains(one)) {
                    throw new ErrorResponseException(HttpStatus.BAD_REQUEST, ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                            "Status must be one of Draft, Released, In Progress, Completed"), null);
                }
            }
        }
        List<WorkPackListRow> rows = new ArrayList<>();
        for (WorkPackListRepository.Row r : list.list(new WorkPackListRepository.Filter(status, project, weekEnding, search))) {
            rows.add(new WorkPackListRow(r.workpackNumber(), r.activityRef(), r.drawingRef(), r.departmentRef(), r.projectRef(), WorkPackListRow.StatusEnum.fromValue(r.status()))
                    .assignmentState(r.assignmentState() == null ? null : WorkPackListRow.AssignmentStateEnum.fromValue(r.assignmentState()))
                    .plannedStartDate(r.plannedStartDate()).plannedEndDate(r.plannedEndDate()).approvedPct(r.approvedPct())
                    .approvedWeekEnding(r.approvedWeekEnding()).earnedManhours(r.earnedManhours()));
        }
        return ResponseEntity.ok(rows);
    }

    @Override
    public ResponseEntity<StartWorkPackResponse> startWorkPack() {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(new StartWorkPackResponse(camunda.start(PROCESS)));
        } catch (EngineUnavailableException e) {
            LOG.warn("Work Pack request not started: {}", e.getMessage());
            throw new ErrorResponseException(HttpStatus.SERVICE_UNAVAILABLE, ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                    "The process engine is not available right now; try again later"), null);
        }
    }
}
