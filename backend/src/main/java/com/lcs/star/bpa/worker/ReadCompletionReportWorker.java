package com.lcs.star.bpa.worker;

import com.lcs.star.bpa.persistence.CompletionReportRepository;
import com.lcs.star.bpa.persistence.ProgressPeriodRepository;
import com.lcs.star.bpa.persistence.ProgressPeriodRepository.Period;
import com.lcs.star.bpa.progress.CompletionProgressService;
import com.lcs.star.bpa.progress.CompletionProgressService.Decision;
import com.lcs.star.bpa.task.TaskService;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Job type {@code read-completion-report} on {@code ServiceTask_ReadCompletionReport} (BPA-59; E2-09; 09 S7, ADR 0006 and 0007). The upload has checked and
 * logged every row already (plan Q7, option A); this step decides what the report is worth ({@link CompletionProgressService}): with no Read row it throws
 * the BPMN error REPORT_INVALID with {@code validationMessages} "No usable rows in the completion report" and the instance returns to the upload task
 * (BoundaryEvent_ReportInvalid); otherwise it applies the Read rows to the week's {@code workpack_progress.reported_pct}. The report must belong to this
 * instance's progress period, else the job fails with no retries (the task API has checked it already: this means the instance was completed some other
 * way). Re-run key: the report's own outcome, decided once.
 */
@Component
public class ReadCompletionReportWorker {

    static final String ERROR_CODE = "REPORT_INVALID";
    static final String NO_USABLE_ROWS = "No usable rows in the completion report";

    private static final Logger LOG = LoggerFactory.getLogger(ReadCompletionReportWorker.class);

    private final CompletionProgressService progress;
    private final CompletionReportRepository reports;
    private final ProgressPeriodRepository periods;

    public ReadCompletionReportWorker(CompletionProgressService progress, CompletionReportRepository reports, ProgressPeriodRepository periods) {
        this.progress = progress;
        this.reports = reports;
        this.periods = periods;
    }

    @JobWorker(type = "read-completion-report", autoComplete = false)
    public void handle(JobClient client, ActivatedJob job) {
        Map<String, Object> variables = job.getVariablesAsMap();
        String username = variables.get(TaskService.COMPLETED_BY) instanceof String who && !who.isBlank() ? who : OpenProgressPeriodWorker.SERVICE_USER;
        UUID reportId;
        try {
            if (!(variables.get("completionReportId") instanceof String id) || id.isBlank()) {
                throw new IllegalArgumentException("No completionReportId on the completion report");
            }
            reportId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            fail(client, job, e.getMessage());
            return;
        }
        Optional<Period> period = periods.ofInstance(job.getProcessInstanceKey());
        if (period.isEmpty() || !reports.periodOf(reportId).equals(Optional.of(period.get().id()))) {
            fail(client, job, "Completion report " + reportId + " does not belong to the week of this instance");
            return;
        }
        Decision decision;
        try {
            decision = progress.decide(reportId, period.get().id(), username);
        } catch (IllegalArgumentException e) {
            // BPA-67: a Locked week refuses the reported % (QA review): fail at once with no retries, as the other guarded record steps do
            fail(client, job, e.getMessage());
            return;
        }
        if (decision == Decision.INVALID) {
            LOG.info("read-completion-report job {}: report {} has no usable row", job.getKey(), reportId);
            client.newThrowErrorCommand(job.getKey()).errorCode(ERROR_CODE).errorMessage(NO_USABLE_ROWS)
                    .variables(Map.of("validationMessages", List.of(NO_USABLE_ROWS))).send().join();
            return;
        }
        LOG.info("read-completion-report job {}: report {} accepted for week ending {}", job.getKey(), reportId, period.get().weekEnding());
        client.newCompleteCommand(job.getKey()).send().join();
    }

    private static void fail(JobClient client, ActivatedJob job, String message) {
        LOG.warn("read-completion-report job {}: {}", job.getKey(), message);
        client.newFailCommand(job.getKey()).retries(0).errorMessage(message).send().join();
    }
}
