package com.lcs.star.bpa.worker;

import com.lcs.star.bpa.persistence.HourJournalHeaderRepository;
import com.lcs.star.bpa.persistence.WorkPackRepository;
import com.lcs.star.bpa.task.TaskService;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Job type {@code create-hour-journal-header} (BPA-7) on {@code ServiceTask_CreateHourJournalHeader}, after the Work Pack is
 * saved, and on {@code ServiceTask_UpdateHourJournalHeader}, after Draft corrections (D21, BPA-23): one upsert for both.
 * The header's estimate is the sum of the Work Pack's phases; a locked header (D8, BPA-9) is left as it is.
 *
 * <p>Built with the new-job-worker skill. Idempotent through UNIQUE workpack_id and "unchanged or locked, change nothing"
 * (D65, S7). No input to validate, so no BPMN error. Who: completedBy, else bpa-service (D63, B12). Retries and incidents are
 * Camunda's defaults (S7): a job whose instance has no Work Pack throws, and ends as an incident after three tries.
 */
@Component
public class HourJournalHeaderWorker {

    /** B12: who a worker writes as when no user is in context. */
    static final String SERVICE_USER = "bpa-service";

    private static final Logger LOG = LoggerFactory.getLogger(HourJournalHeaderWorker.class);

    private final WorkPackRepository workPacks;
    private final HourJournalHeaderRepository headers;

    public HourJournalHeaderWorker(WorkPackRepository workPacks, HourJournalHeaderRepository headers) {
        this.workPacks = workPacks;
        this.headers = headers;
    }

    @JobWorker(type = "create-hour-journal-header", autoComplete = false)
    public void handle(JobClient client, ActivatedJob job) {
        var workPack = workPacks.find(job.getProcessInstanceKey()).orElseThrow(() -> new IllegalStateException(
                "No Work Pack for process instance " + job.getProcessInstanceKey() + " (" + job.getElementId() + ")"));
        Object completedBy = job.getVariablesAsMap().get(TaskService.COMPLETED_BY);
        String username = completedBy instanceof String s && !s.isBlank() ? s : SERVICE_USER;

        var outcome = headers.upsert(workPack.id(), username);
        LOG.info("Hour journal header of {} {} ({}, job {})", workPack.workpackNumber(), outcome, job.getElementId(), job.getKey());
        client.newCompleteCommand(job.getKey()).send().join();
    }
}
