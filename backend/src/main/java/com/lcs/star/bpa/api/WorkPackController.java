package com.lcs.star.bpa.api;

import com.lcs.star.bpa.adapter.CamundaProcessClient;
import com.lcs.star.bpa.adapter.CamundaTaskClient.EngineUnavailableException;
import com.lcs.star.bpa.api.generated.WorkpacksApi;
import com.lcs.star.bpa.persistence.WorkPackDetailRepository;
import com.lcs.star.bpa.persistence.WorkPackListRepository;
import com.lcs.star.bpa.scope.ProjectNotAssignedException;
import com.lcs.star.bpa.scope.ProjectScope;
import com.lcs.star.bpa.task.WorkPackOpenTaskService;
import com.lcs.star.bpa.workpack.WorkPackDetailService;
import com.lcs.star.bpa.workpack.WorkPackListMapper;
import com.lcs.star.bpa.api.generated.model.StartWorkPackRequest;
import com.lcs.star.bpa.api.generated.model.StartWorkPackResponse;
import com.lcs.star.bpa.api.generated.model.OpenTask;
import com.lcs.star.bpa.api.generated.model.WorkPackDetail;
import com.lcs.star.bpa.api.generated.model.WorkPackHistoryPage;
import com.lcs.star.bpa.api.generated.model.WorkPackListRow;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/workpacks}: the Time &amp; Method Analyst starts a Work Pack request (BPA-6; D52, 09 S20). SecurityConfig
 * lets only {@code time-method-analyst} through (403 otherwise). Since BPA-78 the call carries the project ({@code StartWorkPackRequest}), checked with ProjectScope before
 * Camunda is called (BPA-46 Q1 originally gave it no body): the Work Pack data is
 * entered in the Create Work Pack task that follows, through the task API.
 * {@code GET /api/v1/workpacks} (BPA-72) is the read-only Work Packs list for every logged-in group.
 */
@RestController
public class WorkPackController implements WorkpacksApi {

    static final String PROCESS = "Process_PPC_WorkPackAssignment";
    static final String PROJECT_REQUIRED = "Project is required";

    private static final Logger LOG = LoggerFactory.getLogger(WorkPackController.class);

    static final List<String> STATUSES = List.of("Draft", "Released", "In Progress", "Completed");
    private static final java.util.regex.Pattern NUMBER = java.util.regex.Pattern.compile("^WP-[0-9]{6}$");

    private final CamundaProcessClient camunda;
    private final WorkPackListRepository list;
    private final WorkPackDetailService details;
    private final WorkPackOpenTaskService openTasks;
    private final ProjectScope projectScope;

    public WorkPackController(CamundaProcessClient camunda, WorkPackListRepository list, WorkPackDetailService details, WorkPackOpenTaskService openTasks,
            ProjectScope projectScope) {
        this.camunda = camunda;
        this.list = list;
        this.details = details;
        this.openTasks = openTasks;
        this.projectScope = projectScope;
    }


    /**
     * {@code GET /api/v1/workpacks} (BPA-72): the Work Packs list, read only, for every logged-in user (SecurityConfig: authenticated). Since BPA-79 it lists
     * only the Work Packs of the user's projects (ProjectScope, BPA-77); {@code project} narrows within them (a project that is not the user's gives an empty list). A status that is not one of the four is a 400; no match is a 200 with an empty list.
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
        for (WorkPackListRepository.Row r : list.list(new WorkPackListRepository.Filter(status, project, weekEnding, search, projectScope.projectsOf(sessionUser())))) {
            rows.add(WorkPackListMapper.toRow(r));
        }
        return ResponseEntity.ok(rows);
    }

    /**
     * {@code GET /api/v1/workpacks/{workpackNumber}} (BPA-73): the read-only Work Pack detail (summary, Overview, Workers, Hours, Progress) for every logged-in
     * user; 400 for a number outside WP-######, 404 "Work Pack not found".
     */
    @Override
    public ResponseEntity<WorkPackDetail> getWorkPack(String workpackNumber) {
        return ResponseEntity.ok(details.detail(workpackNumber, readable(workpackNumber)));
    }

    /** The History tab (BPA-73): the audit log of the Work Pack and its records, and the drawing notices, newest first, one page. */
    @Override
    public ResponseEntity<WorkPackHistoryPage> getWorkPackHistory(String workpackNumber, Integer page, Integer size) {
        int pageNo = page == null ? 0 : page;
        int pageSize = size == null ? 50 : size;
        if (pageNo < 0 || pageSize < 1 || pageSize > 200) {
            throw new ErrorResponseException(HttpStatus.BAD_REQUEST, ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "page must be 0 or more and size 1 to 200"), null);
        }
        return ResponseEntity.ok(details.history(readable(workpackNumber), pageNo, pageSize));
    }

    /**
     * The open tasks of the Work Pack from every group, without task data (BPA-73, the side panel). The user's groups come from the session; the read is made by the
     * service user. 503 when the process engine is not reachable (the page itself does not depend on it).
     */
    @Override
    public ResponseEntity<List<OpenTask>> listWorkPackOpenTasks(String workpackNumber) {
        WorkPackDetailRepository.Header header = readable(workpackNumber);
        List<String> groups = SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream().map(a -> a.getAuthority()).toList();
        try {
            return ResponseEntity.ok(openTasks.list(workpackNumber, header, groups));
        } catch (EngineUnavailableException e) {
            LOG.warn("Open tasks of {} not read: {}", workpackNumber, e.getMessage());
            throw new ErrorResponseException(HttpStatus.SERVICE_UNAVAILABLE, ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                    "The process engine is not available right now; try again later"), null);
        }
    }

    /**
     * A parameter that breaks a generated constraint (the number's @Pattern, the history's page and size limits): 400, as the other Work Pack reads answer the
     * number. Without this the validation failure would reach the generic handler as a 500.
     */
    @ExceptionHandler({ConstraintViolationException.class, HandlerMethodValidationException.class})
    ResponseEntity<ProblemDetail> invalidParameter(Exception e) {
        boolean paging = (e instanceof HandlerMethodValidationException h && h.getParameterValidationResults().stream()
                .anyMatch(r -> List.of("page", "size").contains(r.getMethodParameter().getParameterName())))
                || (e instanceof ConstraintViolationException c && c.getConstraintViolations().stream()
                        .anyMatch(v -> v.getPropertyPath().toString().endsWith("page") || v.getPropertyPath().toString().endsWith("size")));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, paging ? "page must be 0 or more and size 1 to 200" : "Workpack number must match WP-######"));
    }

    /** BPA-79: {@link #known} and then the project of the Work Pack must be one of the user's (403, BPA-77's text); an unknown number stays 404. */
    private WorkPackDetailRepository.Header readable(String workpackNumber) {
        WorkPackDetailRepository.Header header = known(workpackNumber);
        projectScope.require(sessionUser(), header.projectRef());
        return header;
    }

    private static String sessionUser() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    /** The Work Pack of this number: 400 outside WP-###### (the contract's InvalidWorkpackNumber), 404 "Work Pack not found". */
    private WorkPackDetailRepository.Header known(String workpackNumber) {
        if (workpackNumber == null || !NUMBER.matcher(workpackNumber).matches()) {
            throw new ErrorResponseException(HttpStatus.BAD_REQUEST, ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Workpack number must match WP-######"), null);
        }
        return details.header(workpackNumber).orElseThrow(() -> new ErrorResponseException(HttpStatus.NOT_FOUND,
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Work Pack not found"), null));
    }

    /**
     * BPA-78: the order is the group (SecurityConfig, 403), the body (400 "Project is required"), the project (ProjectScope, 403 "Project PRJ-142 is not assigned to
     * you"), then Camunda. A refusal returns before Camunda is called, so no process instance starts. The instance carries {@code projectRef}.
     */
    @Override
    public ResponseEntity<StartWorkPackResponse> startWorkPack(StartWorkPackRequest startWorkPackRequest) {
        String projectRef = startWorkPackRequest == null ? null : startWorkPackRequest.getProjectRef();
        if (projectRef == null || projectRef.isBlank()) {
            throw problem(HttpStatus.BAD_REQUEST, PROJECT_REQUIRED);
        }
        projectScope.require(SecurityContextHolder.getContext().getAuthentication().getName(), projectRef);
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(new StartWorkPackResponse(camunda.start(PROCESS, Map.of("projectRef", projectRef))));
        } catch (EngineUnavailableException e) {
            LOG.warn("Work Pack request not started: {}", e.getMessage());
            throw new ErrorResponseException(HttpStatus.SERVICE_UNAVAILABLE, ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                    "The process engine is not available right now; try again later"), null);
        }
    }

    /** ASSUMPTION (plan BPA-78): a missing body, an unreadable one, or no {@code projectRef} in it is "Project is required"; the start is the only operation here with a body. */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class})
    ResponseEntity<ProblemDetail> projectRequired(Exception e) {
        LOG.warn("Work Pack start refused, the body is missing or unreadable: {}", e.getMessage());
        return problemResponse(HttpStatus.BAD_REQUEST, PROJECT_REQUIRED);
    }

    /** BPA-77's rule at the start: a project the user has no user_project row for, or that does not exist, is 403 "Project PRJ-142 is not assigned to you" (ASSUMPTION for the text). */
    @ExceptionHandler(ProjectNotAssignedException.class)
    ResponseEntity<ProblemDetail> notAssigned(ProjectNotAssignedException e) {
        return problemResponse(HttpStatus.FORBIDDEN, e.getMessage());
    }

    private static ResponseEntity<ProblemDetail> problemResponse(HttpStatus status, String detail) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(ProblemDetail.forStatusAndDetail(status, detail));
    }

    private static ErrorResponseException problem(HttpStatus status, String detail) {
        return new ErrorResponseException(status, ProblemDetail.forStatusAndDetail(status, detail), null);
    }
}
