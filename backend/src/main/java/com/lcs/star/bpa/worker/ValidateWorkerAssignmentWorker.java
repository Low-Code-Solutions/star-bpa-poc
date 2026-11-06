package com.lcs.star.bpa.worker;

import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import org.springframework.stereotype.Component;

/**
 * BPA-12 (test-first stub, replaced by the placeholder when the intake is built): no job worker is registered yet, so a routed
 * instance waits at ServiceTask_ValidateWorkerAssignment. Row validation is BPA-22.
 */
@Component
public class ValidateWorkerAssignmentWorker {

    public void handle(JobClient client, ActivatedJob job) {
        throw new UnsupportedOperationException("validate-worker-assignment is built by BPA-22");
    }
}
