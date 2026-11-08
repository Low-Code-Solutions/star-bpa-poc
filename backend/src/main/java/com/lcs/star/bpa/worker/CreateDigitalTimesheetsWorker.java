package com.lcs.star.bpa.worker;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Job type {@code create-digital-timesheets} on {@code ServiceTask_CreateDigitalTimesheets}: a placeholder until BPA-15 builds the step (the
 * new-job-worker skill: "a job type shared with a step a later story builds"; BPA-22 did the same for the Hour Journal Lines). An instance
 * whose Lines exist (BPA-14) reaches this task. The job fails with no retries, so it ends as an incident that says "built by BPA-15", and
 * nothing is saved. BPA-15 replaces this class.
 */
@Component
public class CreateDigitalTimesheetsWorker {

    static final String MESSAGE = "create-digital-timesheets is built by BPA-15";

    private static final Logger LOG = LoggerFactory.getLogger(CreateDigitalTimesheetsWorker.class);

    @JobWorker(type = "create-digital-timesheets", autoComplete = false)
    public void handle(JobClient client, ActivatedJob job) {
        LOG.warn("{} (job {}, instance {}): incident raised", MESSAGE, job.getKey(), job.getProcessInstanceKey());
        client.newFailCommand(job.getKey()).retries(0).errorMessage(MESSAGE).send().join();
    }
}
