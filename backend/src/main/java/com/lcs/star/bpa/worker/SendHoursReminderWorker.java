package com.lcs.star.bpa.worker;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import org.springframework.stereotype.Component;

/**
 * Job type {@code send-hours-reminder} on the three {@code ServiceTask_SendHoursReminder*} (BPA-74). Compile stub for the red tests: not built yet, it fails
 * the job without retries.
 */
@Component
public class SendHoursReminderWorker {

    @JobWorker(type = "send-hours-reminder", autoComplete = false)
    public void handle(JobClient client, ActivatedJob job) {
        client.newFailCommand(job.getKey()).retries(0).errorMessage("send-hours-reminder is not built yet").send().join();
    }
}
