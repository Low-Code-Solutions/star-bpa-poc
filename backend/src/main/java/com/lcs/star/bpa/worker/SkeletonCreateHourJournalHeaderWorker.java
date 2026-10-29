package com.lcs.star.bpa.worker;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Skeleton stub; BPA-7 replaces the body test-first.
 *
 * <p>Handles the real job type of {@code ServiceTask_CreateHourJournalHeader} so the walking skeleton proves deploy, activation
 * and completion. It deliberately has no behaviour: no process variables read or written, no database access. The Project
 * Hour Journal Header upsert and its rules are BPA-7's acceptance criteria.
 */
@Component
public class SkeletonCreateHourJournalHeaderWorker {

    private static final Logger LOG = LoggerFactory.getLogger(SkeletonCreateHourJournalHeaderWorker.class);

    @JobWorker(type = "create-hour-journal-header")
    public void handle(ActivatedJob job) {
        LOG.info("Skeleton stub completed create-hour-journal-header job {}", job.getKey());
    }
}
