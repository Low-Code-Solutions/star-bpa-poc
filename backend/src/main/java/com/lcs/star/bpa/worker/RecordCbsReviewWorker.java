package com.lcs.star.bpa.worker;

import com.lcs.star.bpa.persistence.ProgressPeriodRepository.Period;
import com.lcs.star.bpa.progress.CbsReviewService;
import com.lcs.star.bpa.progress.CbsReviewService.Review;
import com.lcs.star.bpa.progress.ProgressCheckService;
import com.lcs.star.bpa.task.TaskService;
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
 * Job type {@code record-cbs-review} on {@code ServiceTask_RecordCbsReview} (BPA-64; E2-14; plan Q9; ADR 0006 and 0007). The task API has checked the review before the
 * task completed (a remark for every CBS with an alert, at most 500 characters); this step writes {@code cbsReviews} to {@code cbs_review} for the week of the instance's
 * progress period ({@link CbsReviewService}), with {@code completedBy} as the reviewer. Re-run key: the (period, CBS) row and its values, so a retried job writes nothing
 * again; a review sent back by the Project Manager (BPA-66) updates the row. A week with no period, a Locked week or a review the checks refuse fails the job with no
 * retries (the instance was completed some other way). No output variable.
 */
@Component
public class RecordCbsReviewWorker {

    private static final Logger LOG = LoggerFactory.getLogger(RecordCbsReviewWorker.class);

    private final ProgressCheckService periods;
    private final CbsReviewService service;

    public RecordCbsReviewWorker(ProgressCheckService periods, CbsReviewService service) {
        this.periods = periods;
        this.service = service;
    }

    @JobWorker(type = "record-cbs-review", autoComplete = false)
    public void handle(JobClient client, ActivatedJob job) {
        Map<String, Object> variables = job.getVariablesAsMap();
        String username = variables.get(TaskService.COMPLETED_BY) instanceof String who && !who.isBlank() ? who : OpenProgressPeriodWorker.SERVICE_USER;
        Optional<Period> period = periods.periodOf(job.getProcessInstanceKey(), variables.get("progressPeriodId"));
        if (period.isEmpty()) {
            fail(client, job, "The week of this instance has no progress period");
            return;
        }
        try {
            List<Review> reviews = CbsReviewService.parse(variables.get("cbsReviews"));
            if (reviews.isEmpty()) {
                throw new IllegalArgumentException("No cbsReviews on the review");
            }
            service.record(period.get(), reviews, username);
            LOG.info("record-cbs-review job {}: {} CBS reviewed for week ending {} by {}", job.getKey(), reviews.size(), period.get().weekEnding(), username);
        } catch (IllegalArgumentException e) {
            fail(client, job, e.getMessage());
            return;
        }
        client.newCompleteCommand(job.getKey()).send().join();
    }

    private static void fail(JobClient client, ActivatedJob job, String message) {
        LOG.warn("record-cbs-review job {}: {}", job.getKey(), message);
        client.newFailCommand(job.getKey()).retries(0).errorMessage(message).send().join();
    }
}
