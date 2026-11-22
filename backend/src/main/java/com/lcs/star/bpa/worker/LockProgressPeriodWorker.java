package com.lcs.star.bpa.worker;

import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import org.springframework.stereotype.Component;

/**
 * BPA-67 (E2-16): COMPILE STUB for the red run (no @JobWorker, so the step still waits as before); the real worker comes in the green commit.
 */
@Component
public class LockProgressPeriodWorker {

    public void handle(JobClient client, ActivatedJob job) {
        throw new UnsupportedOperationException("BPA-67: not built yet");
    }
}
