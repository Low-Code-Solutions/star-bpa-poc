package com.lcs.star.bpa.worker;

import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import org.springframework.stereotype.Component;

/**
 * BPA-10 (test-first stub, replaced when the worker is built): no job worker is registered yet, so ServiceTask_GenerateWorkPackPrint
 * is never reached by a job, which is the expected red state for the BPA10_ tests.
 */
@Component
public class GenerateWorkPackPrintWorker {

    public void handle(JobClient client, ActivatedJob job) {
        throw new UnsupportedOperationException("generate-workpack-print is not built yet (BPA-10)");
    }
}
