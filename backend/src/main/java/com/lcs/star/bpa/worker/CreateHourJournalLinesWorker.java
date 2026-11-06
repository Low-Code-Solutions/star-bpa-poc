package com.lcs.star.bpa.worker;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Job type {@code create-hour-journal-lines} on {@code ServiceTask_CreateHourJournalLines}: a placeholder until BPA-14 builds the step (the
 * new-job-worker skill: "a job type shared with a step a later story builds"; BPA-12 did the same for the row validation). An instance
 * whose upload has at least one Assigned Worker (BPA-22) reaches this task. The job fails with no retries, so it ends as an incident that
 * says "built by BPA-14", and nothing is saved. BPA-14 replaces this class.
 */
@Component
public class CreateHourJournalLinesWorker {

    static final String MESSAGE = "create-hour-journal-lines is built by BPA-14";

    private static final Logger LOG = LoggerFactory.getLogger(CreateHourJournalLinesWorker.class);

    @JobWorker(type = "create-hour-journal-lines", autoComplete = false)
    public void handle(JobClient client, ActivatedJob job) {
        LOG.warn("{} (job {}, instance {}): incident raised", MESSAGE, job.getKey(), job.getProcessInstanceKey());
        client.newFailCommand(job.getKey()).retries(0).errorMessage(MESSAGE).send().join();
    }
}
