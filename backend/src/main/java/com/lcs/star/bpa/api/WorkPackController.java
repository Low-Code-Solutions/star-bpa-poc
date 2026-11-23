package com.lcs.star.bpa.api;

import com.lcs.star.bpa.adapter.CamundaProcessClient;
import com.lcs.star.bpa.adapter.CamundaTaskClient.EngineUnavailableException;
import com.lcs.star.bpa.api.generated.WorkpacksApi;
import com.lcs.star.bpa.api.generated.model.StartWorkPackResponse;
import com.lcs.star.bpa.api.generated.model.WorkPackListRow;
import java.time.LocalDate;
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
 */
@RestController
public class WorkPackController implements WorkpacksApi {

    static final String PROCESS = "Process_PPC_WorkPackAssignment";

    private static final Logger LOG = LoggerFactory.getLogger(WorkPackController.class);

    private final CamundaProcessClient camunda;

    public WorkPackController(CamundaProcessClient camunda) {
        this.camunda = camunda;
    }


    /** BPA-72 compile stub: not built yet (red tests first). */
    @Override
    public ResponseEntity<List<WorkPackListRow>> listWorkPacks(List<String> status, String project, LocalDate weekEnding, String search) {
        throw new ErrorResponseException(HttpStatus.NOT_IMPLEMENTED, ProblemDetail.forStatusAndDetail(HttpStatus.NOT_IMPLEMENTED, "Work Pack list not built yet"), null);
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
