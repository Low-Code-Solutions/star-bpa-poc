package com.lcs.star.bpa.worker;

import com.lcs.star.bpa.persistence.ProgressPeriodRepository.Period;
import com.lcs.star.bpa.progress.ProgressCheckService;
import com.lcs.star.bpa.progress.WeekDecisionService;
import com.lcs.star.bpa.progress.WorkPackCompletionService;
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
 * Job type {@code complete-workpacks} on {@code ServiceTask_CompleteWorkPacks} (BPA-67; E2-16; ADR 0007 and 0008). After the Project Manager approves the week
 * ({@code Gateway_IsWeekApproved}), every Work Pack approved at exactly 100% for the week becomes Completed and gets its {@code WorkPackCompleted} message
 * ({@link WorkPackCompletionService}); {@code updated_by} is {@code bpa-service}. Re-run key: the values (a Completed Work Pack is not written again) and
 * {@code published_message} (a message already recorded is not published again). A week with no period or a Locked week fails the job with no retries; a publish failure is
 * thrown, so Camunda retries (the Work Packs are committed already) and then raises an incident. No output variable.
 */
@Component
public class CompleteWorkPacksWorker {

    private static final Logger LOG = LoggerFactory.getLogger(CompleteWorkPacksWorker.class);

    private final ProgressCheckService periods;
    private final WorkPackCompletionService service;

    public CompleteWorkPacksWorker(ProgressCheckService periods, WorkPackCompletionService service) {
        this.periods = periods;
        this.service = service;
    }

    @JobWorker(type = "complete-workpacks", autoComplete = false)
    public void handle(JobClient client, ActivatedJob job) {
        Map<String, Object> variables = job.getVariablesAsMap();
        Optional<Period> period = periods.periodOf(job.getProcessInstanceKey(), variables.get("progressPeriodId"));
        if (period.isEmpty()) {
            fail(client, job, WeekDecisionService.NO_PERIOD);
            return;
        }
        List<String> atHundred;
        try {
            atHundred = service.complete(period.get(), OpenProgressPeriodWorker.SERVICE_USER);
        } catch (IllegalArgumentException e) {
            fail(client, job, e.getMessage());
            return;
        }
        service.publish(atHundred, OpenProgressPeriodWorker.SERVICE_USER);
        LOG.info("complete-workpacks job {}: {} Work Pack(s) at 100% for week ending {}", job.getKey(), atHundred.size(), period.get().weekEnding());
        client.newCompleteCommand(job.getKey()).send().join();
    }

    private static void fail(JobClient client, ActivatedJob job, String message) {
        LOG.warn("complete-workpacks job {}: {}", job.getKey(), message);
        client.newFailCommand(job.getKey()).retries(0).errorMessage(message).send().join();
    }
}
