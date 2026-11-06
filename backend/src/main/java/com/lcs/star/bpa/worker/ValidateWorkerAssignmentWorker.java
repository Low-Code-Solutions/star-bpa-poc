package com.lcs.star.bpa.worker;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Job type {@code validate-worker-assignment} on {@code ServiceTask_ValidateWorkerAssignment}: a placeholder until BPA-22 builds the row
 * validation (the new-job-worker skill: "a job type shared with a step a later story builds"; BPA-6 did the same for the corrections
 * step). BPA-12 routes an uploaded file to the instance waiting at the message, and the instance continues to this task. The job fails
 * with no retries, so it ends as an incident that says "built by BPA-22" and the rows of the upload stay "Pending". BPA-22 replaces
 * this class.
 */
@Component
public class ValidateWorkerAssignmentWorker {

    static final String MESSAGE = "validate-worker-assignment is built by BPA-22";

    private static final Logger LOG = LoggerFactory.getLogger(ValidateWorkerAssignmentWorker.class);

    @JobWorker(type = "validate-worker-assignment", autoComplete = false)
    public void handle(JobClient client, ActivatedJob job) {
        LOG.warn("{} (job {}, instance {}): incident raised", MESSAGE, job.getKey(), job.getProcessInstanceKey());
        client.newFailCommand(job.getKey()).retries(0).errorMessage(MESSAGE).send().join();
    }
}
