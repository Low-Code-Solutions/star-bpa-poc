package com.lcs.star.bpa.worker;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import org.springframework.stereotype.Component;

/**
 * STUB for the red run (L15): job type {@code replace-worker-assignment} on ServiceTask_ReplaceAssignment. Fails every job with no retries
 * until BPA-13's build step replaces this class.
 */
@Component
public class ReplaceWorkerAssignmentWorker {

    static final String MESSAGE = "replace-worker-assignment is built by BPA-13";

    @JobWorker(type = "replace-worker-assignment", autoComplete = false)
    public void handle(JobClient client, ActivatedJob job) {
        client.newFailCommand(job.getKey()).retries(0).errorMessage(MESSAGE).send().join();
    }
}
