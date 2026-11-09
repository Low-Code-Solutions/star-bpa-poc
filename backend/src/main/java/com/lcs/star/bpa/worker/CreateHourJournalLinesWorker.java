package com.lcs.star.bpa.worker;

import com.lcs.star.bpa.persistence.HourJournalLineRepository;
import com.lcs.star.bpa.persistence.HourJournalLineRepository.Created;
import com.lcs.star.bpa.persistence.WorkPackRepository;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Job type {@code create-hour-journal-lines} on {@code ServiceTask_CreateHourJournalLines} (BPA-14; D39, C14; 09 S7). Runs once BPA-22 has
 * assigned at least one Worker (Gateway_HasAssignedWorkers, Yes): one Project Hour Journal Line per assigned Worker under the Work Pack's
 * Header, with the Worker's role and designation copied from the Worker master and the assignment's estimated hours, status Open. Rows BPA-22
 * rejected were never saved as assignments, so they get no Line. Idempotent on unique(header_id, worker_id) (see
 * {@link HourJournalLineRepository#createFor}): a retry creates nothing new and completes again. Retries and backoff are Camunda's defaults
 * (S7, ADR 0007). A Work Pack with no Header (BPA-7 makes it before any assignment can exist) fails the job with no retries and says why, so
 * Operate shows an incident. BPA-15 reads the Lines from the database, so the job returns no variables.
 */
@Component
public class CreateHourJournalLinesWorker {

    private static final Logger LOG = LoggerFactory.getLogger(CreateHourJournalLinesWorker.class);

    private final WorkPackRepository workPacks;
    private final HourJournalLineRepository lines;

    public CreateHourJournalLinesWorker(WorkPackRepository workPacks, HourJournalLineRepository lines) {
        this.workPacks = workPacks;
        this.lines = lines;
    }

    @JobWorker(type = "create-hour-journal-lines", autoComplete = false)
    public void handle(JobClient client, ActivatedJob job) {
        var workPack = workPacks.find(job.getProcessInstanceKey()).orElseThrow(() -> new IllegalStateException(
                "No Work Pack for process instance " + job.getProcessInstanceKey() + " (" + job.getElementId() + ")"));
        Created created = lines.createFor(workPack.id());
        if (!created.hasHeader()) {
            String message = workPack.workpackNumber() + " has no Project Hour Journal Header, so its Lines cannot be created (BPA-7)";
            LOG.warn("create-hour-journal-lines job {} (instance {}): {}", job.getKey(), job.getProcessInstanceKey(), message);
            client.newFailCommand(job.getKey()).retries(0).errorMessage(message).send().join();
            return;
        }
        LOG.info("create-hour-journal-lines job {}: {} has {} new Line(s)", job.getKey(), workPack.workpackNumber(), created.created());
        client.newCompleteCommand(job.getKey()).send().join();
    }
}
