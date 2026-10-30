package com.lcs.star.bpa.api;

import com.lcs.star.bpa.api.generated.WorkpacksApi;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/workpacks}, walking skeleton only (BPA-43 Q6): SecurityConfig lets only {@code time-method-analyst} through
 * (403 for anyone else) and this answers 501. Skeleton stub; BPA-6 replaces the body test-first.
 */
@RestController
public class WorkPackController implements WorkpacksApi {

    @Override
    public ResponseEntity<Void> startWorkPack(Map<String, Object> requestBody) {
        throw new ErrorResponseException(HttpStatus.NOT_IMPLEMENTED,
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_IMPLEMENTED, "Starting a Work Pack is built by BPA-6"), null);
    }
}
