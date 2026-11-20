package com.lcs.star.bpa.worker;

import com.lcs.star.bpa.persistence.ProgressPeriodRepository.Period;
import com.lcs.star.bpa.progress.ProgressCheckService;
import com.lcs.star.bpa.progress.ProgressCheckService.Result;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Job type {@code record-progress-result} on {@code ServiceTask_RecordProgressResult} (BPA-61; E2-11; plan Q3 b; ADR 0006 and 0007). Reads
 * {@code progressCheckResults} (what Decision_ProgressRule gave for each Work Pack), writes {@code workpack_progress.rule_result} for the week of the
 * instance's progress period in one transaction, and sets {@code validationMessages}: one message per Work Pack that is not Accepted, with Jira's texts
 * ({@link com.lcs.star.bpa.progress.ProgressMessages}); the list is set on every pass (empty when all are Accepted), so an old message never shows on the
 * task a Blocked week returns to. Re-run key: the values: the same results written again change nothing. A payload that is not understood, a Work Pack with no
 * progress row or a week with no progress period fails the job with no retries.
 */
@Component
public class RecordProgressResultWorker {

    private static final Logger LOG = LoggerFactory.getLogger(RecordProgressResultWorker.class);

    private final ProgressCheckService service;

    public RecordProgressResultWorker(ProgressCheckService service) {
        this.service = service;
    }

    @JobWorker(type = "record-progress-result", autoComplete = false)
    public void handle(JobClient client, ActivatedJob job) {
        Map<String, Object> variables = job.getVariablesAsMap();
        Optional<Period> period = service.periodOf(job.getProcessInstanceKey(), variables.get("progressPeriodId"));
        if (period.isEmpty()) {
            fail(client, job, "The week of this instance has no progress period");
            return;
        }
        List<String> messages;
        try {
            List<Result> results = ProgressCheckService.parse(variables.get("progressCheckResults"));
            if (results.isEmpty()) {
                throw new IllegalArgumentException("No progressCheckResults to record");
            }
            messages = service.record(period.get(), results, OpenProgressPeriodWorker.SERVICE_USER);
        } catch (IllegalArgumentException e) {
            fail(client, job, e.getMessage());
            return;
        }
        LOG.info("record-progress-result job {}: results recorded for week ending {}, {} message(s)", job.getKey(), period.get().weekEnding(), messages.size());
        client.newCompleteCommand(job.getKey()).variables(Map.of("validationMessages", messages)).send().join();
    }

    private static void fail(JobClient client, ActivatedJob job, String message) {
        LOG.warn("record-progress-result job {}: {}", job.getKey(), message);
        client.newFailCommand(job.getKey()).retries(0).errorMessage(message).send().join();
    }
}
