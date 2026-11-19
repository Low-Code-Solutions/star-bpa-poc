package com.lcs.star.bpa.worker;

import com.lcs.star.bpa.fms.FmsHoursExportService;
import com.lcs.star.bpa.fms.FmsHoursExportService.Outcome;
import com.lcs.star.bpa.task.TaskService;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Job type {@code record-fms-hand-off} on {@code ServiceTask_RecordFmsHandOff} (BPA-58; E2-08; plan Q7). Writes PPC's outcome for each file of the
 * Send hours to FMS task (variable {@code fmsHandOffs}): Sent to FMS records who and when ({@code completedBy}, D63); Rejected by FMS stamps the
 * rejection and its reason, releases the file's lines for the next file and keeps the file (S4). Re-run key: a file that already has an outcome is left
 * as it is (ADR 0007, D65). An entry that is not understood fails the job with no retries: the task API has checked the completion already, so it
 * means the instance was completed some other way.
 */
@Component
public class RecordFmsHandOffWorker {

    private static final Logger LOG = LoggerFactory.getLogger(RecordFmsHandOffWorker.class);

    private final FmsHoursExportService service;

    public RecordFmsHandOffWorker(FmsHoursExportService service) {
        this.service = service;
    }

    @JobWorker(type = "record-fms-hand-off", autoComplete = false)
    public void handle(JobClient client, ActivatedJob job) {
        Map<String, Object> variables = job.getVariablesAsMap();
        String username = variables.get(TaskService.COMPLETED_BY) instanceof String who && !who.isBlank() ? who : OpenProgressPeriodWorker.SERVICE_USER;
        List<Outcome> outcomes = new ArrayList<>();
        try {
            if (!(variables.get("fmsHandOffs") instanceof List<?> entries) || entries.isEmpty()) {
                throw new IllegalArgumentException("No fmsHandOffs on the hand-off to FMS");
            }
            for (Object entry : entries) {
                if (!(entry instanceof Map<?, ?> map) || !(map.get("fmsHoursExportId") instanceof String id)) {
                    throw new IllegalArgumentException("An fmsHandOffs entry has no fmsHoursExportId");
                }
                outcomes.add(new Outcome(UUID.fromString(id), map.get("outcome") instanceof String o ? o : null, map.get("reason") instanceof String r ? r : null));
            }
            service.recordHandOffs(outcomes, username);
        } catch (IllegalArgumentException e) {
            LOG.warn("record-fms-hand-off job {}: {}", job.getKey(), e.getMessage());
            client.newFailCommand(job.getKey()).retries(0).errorMessage(e.getMessage()).send().join();
            return;
        }
        LOG.info("record-fms-hand-off job {}: {} file(s) recorded by {}", job.getKey(), outcomes.size(), username);
        client.newCompleteCommand(job.getKey()).send().join();
    }
}
