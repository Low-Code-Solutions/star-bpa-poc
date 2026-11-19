package com.lcs.star.bpa.worker;

import com.lcs.star.bpa.fms.FmsHoursExportService;
import com.lcs.star.bpa.persistence.FmsHoursExportRepository.Export;
import com.lcs.star.bpa.persistence.ProgressPeriodRepository;
import com.lcs.star.bpa.persistence.ProgressPeriodRepository.Period;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Job type {@code generate-fms-hours-file} on {@code ServiceTask_GenerateFmsHoursFile} (BPA-58; E2-08; 09 S7, ADR 0007). Builds the week's FMS hours
 * files from every Approved line not yet exported and stamps the lines (FmsHoursExportService: one transaction). Re-run key: the progress period, so a
 * retried job returns the files it already made and builds no second one. A missing progressPeriodId or an unknown period is a fault, not a BPMN error.
 * Output {@code fmsHoursExports}: one entry per file (id, periodMonth, rowCount), which the Send hours to FMS screen lists.
 */
@Component
public class GenerateFmsHoursFileWorker {

    private static final Logger LOG = LoggerFactory.getLogger(GenerateFmsHoursFileWorker.class);

    private final FmsHoursExportService service;
    private final ProgressPeriodRepository periods;

    public GenerateFmsHoursFileWorker(FmsHoursExportService service, ProgressPeriodRepository periods) {
        this.service = service;
        this.periods = periods;
    }

    @JobWorker(type = "generate-fms-hours-file", autoComplete = false)
    public void handle(JobClient client, ActivatedJob job) {
        Map<String, Object> variables = job.getVariablesAsMap();
        if (!(variables.get("progressPeriodId") instanceof String id) || id.isBlank()) {
            throw new IllegalStateException("No progressPeriodId on the FMS hours file (job " + job.getKey() + ")");
        }
        Period period = periods.find(UUID.fromString(id))
                .orElseThrow(() -> new IllegalStateException("Progress period " + id + " not found (job " + job.getKey() + ")"));
        List<Export> made = service.generate(period.id(), period.weekEnding(), OpenProgressPeriodWorker.SERVICE_USER);
        LOG.info("generate-fms-hours-file job {}: {} file(s) for week ending {}", job.getKey(), made.size(), period.weekEnding());
        List<Map<String, Object>> listed = made.stream().map(e -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", e.id().toString());
            entry.put("periodMonth", e.periodMonth().toString());
            entry.put("rowCount", e.rowCount());
            return entry;
        }).toList();
        client.newCompleteCommand(job.getKey()).variables(Map.of("fmsHoursExports", listed)).send().join();
    }
}
