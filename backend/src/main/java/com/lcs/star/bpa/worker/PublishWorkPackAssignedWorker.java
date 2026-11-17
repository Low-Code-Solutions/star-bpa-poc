package com.lcs.star.bpa.worker;

import com.lcs.star.bpa.persistence.WorkPackRepository;
import com.lcs.star.bpa.timesheet.TimesheetProcessStarter;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Job type {@code publish-workpack-assigned} on the message end event {@code EndEvent_WorkPackAssigned} (BPA-49; E2-D18, D37; 09 S7, ADR 0007).
 * Publishes WorkPackAssigned, which starts one Process_PPC_TimesheetToProgress for the Work Pack. Idempotent: the duplicate guard is the
 * {@code published_message} record kept by {@link TimesheetProcessStarter}, so a retried job publishes nothing and completes. Retries and backoff
 * are Camunda's defaults. If the engine cannot take the message the job fails and is retried; after 3 tries Operate shows an incident.
 */
@Component
public class PublishWorkPackAssignedWorker {

    private static final Logger LOG = LoggerFactory.getLogger(PublishWorkPackAssignedWorker.class);

    private final WorkPackRepository workPacks;
    private final TimesheetProcessStarter starter;

    public PublishWorkPackAssignedWorker(WorkPackRepository workPacks, TimesheetProcessStarter starter) {
        this.workPacks = workPacks;
        this.starter = starter;
    }

    @JobWorker(type = "publish-workpack-assigned", autoComplete = false)
    public void handle(JobClient client, ActivatedJob job) {
        var workPack = workPacks.find(job.getProcessInstanceKey()).orElseThrow(() -> new IllegalStateException(
                "No Work Pack for process instance " + job.getProcessInstanceKey() + " (" + job.getElementId() + ")"));
        Object completedBy = job.getVariablesAsMap().get("completedBy");
        String username = completedBy instanceof String s && !s.isBlank() ? s : "bpa-service";
        var outcome = starter.publish(workPack, username);
        LOG.info("publish-workpack-assigned job {}: {} {}", job.getKey(), workPack.workpackNumber(), outcome);
        client.newCompleteCommand(job.getKey()).send().join();
    }
}
