package com.lcs.star.bpa.worker;

import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import org.springframework.stereotype.Component;

/**
 * BPA-11 (test-first stub, replaced when the worker is built): no job worker is registered yet, so ServiceTask_SaveWorkPackSchedule
 * is never reached by a job, which is the expected red state for the BPA11_ tests.
 */
@Component
public class SaveWorkPackScheduleWorker {

    public void handle(JobClient client, ActivatedJob job) {
        throw new UnsupportedOperationException("save-workpack-schedule is not built yet (BPA-11)");
    }
}
