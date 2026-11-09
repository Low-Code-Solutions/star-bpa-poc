package com.lcs.star.bpa.worker;

import com.lcs.star.bpa.persistence.DigitalTimesheetRepository;
import com.lcs.star.bpa.persistence.DigitalTimesheetRepository.Result;
import com.lcs.star.bpa.persistence.WorkPackRepository;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Job type {@code create-digital-timesheets} on {@code ServiceTask_CreateDigitalTimesheets} (BPA-15; D36, D40, D61; 09 S7). Runs once BPA-14 has
 * created the Project Hour Journal Lines: one Digital Time sheet per Worker per Work Pack, made from each Open Line, with Project ID and WBS
 * from the Work Pack's activity, the CBS code from the activity's CBS assignment and the cost centre from the Worker's personal cost centre (a
 * time sheet is still created when the last two are missing, D40). In the same transaction it moves the Work Pack from Assigning to Assigned
 * (see {@link DigitalTimesheetRepository#createFor}) and the process ends at EndEvent_WorkPackAssigned; Epic 2 (BPA-4) is not started here.
 * Idempotent on unique(worker_id, workpack_id): a retry creates nothing new and completes again. Retries and backoff are Camunda's defaults (S7,
 * ADR 0007). A Work Pack that is not Assigning or Assigned, or has no Open Line, fails the job with no retries and says why, so Operate shows an
 * incident and nothing is saved (as BPA-22 does for D61). The job returns no variables.
 */
@Component
public class CreateDigitalTimesheetsWorker {

    private static final Logger LOG = LoggerFactory.getLogger(CreateDigitalTimesheetsWorker.class);

    private final WorkPackRepository workPacks;
    private final DigitalTimesheetRepository timesheets;

    public CreateDigitalTimesheetsWorker(WorkPackRepository workPacks, DigitalTimesheetRepository timesheets) {
        this.workPacks = workPacks;
        this.timesheets = timesheets;
    }

    @JobWorker(type = "create-digital-timesheets", autoComplete = false)
    public void handle(JobClient client, ActivatedJob job) {
        var workPack = workPacks.find(job.getProcessInstanceKey()).orElseThrow(() -> new IllegalStateException(
                "No Work Pack for process instance " + job.getProcessInstanceKey() + " (" + job.getElementId() + ")"));
        Result result = timesheets.createFor(workPack.id());
        switch (result.status()) {
            case WRONG_STATE -> fail(client, job, workPack.workpackNumber() + " is "
                    + (result.state().isEmpty() ? "without an assignment state" : result.state())
                    + ", not Assigning or Assigned, so its Digital Time sheets cannot be created (D61)");
            case NO_LINES -> fail(client, job, workPack.workpackNumber()
                    + " has no Open Project Hour Journal Line, so its Digital Time sheets cannot be created (BPA-14)");
            case DONE -> {
                LOG.info("create-digital-timesheets job {}: {} has {} new Digital Time sheet(s)", job.getKey(), workPack.workpackNumber(), result.created());
                client.newCompleteCommand(job.getKey()).send().join();
            }
        }
    }

    private static void fail(JobClient client, ActivatedJob job, String message) {
        LOG.warn("create-digital-timesheets job {} (instance {}): {}", job.getKey(), job.getProcessInstanceKey(), message);
        client.newFailCommand(job.getKey()).retries(0).errorMessage(message).send().join();
    }
}
