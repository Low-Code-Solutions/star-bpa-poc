package com.lcs.star.bpa.worker;

import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import org.springframework.stereotype.Component;

/**
 * BPA-8 (test-first stub, replaced when the worker is built): no job worker is registered yet, so
 * ServiceTask_EvaluateReleaseInputs waits, which is the expected red state for the BPA8_ tests.
 */
@Component
public class EvaluateReleaseInputsWorker {

    public void handle(JobClient client, ActivatedJob job) {
        throw new UnsupportedOperationException("evaluate-release-inputs is not built yet (BPA-8)");
    }
}
