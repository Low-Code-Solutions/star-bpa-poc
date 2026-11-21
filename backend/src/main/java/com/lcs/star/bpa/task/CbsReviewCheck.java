package com.lcs.star.bpa.task;

import com.lcs.star.bpa.persistence.ProgressPeriodRepository;
import com.lcs.star.bpa.persistence.ProgressPeriodRepository.Period;
import com.lcs.star.bpa.progress.CbsReviewService;
import com.lcs.star.bpa.progress.CbsReviewService.Review;
import com.lcs.star.bpa.progress.VarianceCheckService;
import com.lcs.star.bpa.progress.VarianceCheckService.Check;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * BPA-64: the rules of completing UserTask_ReviewActualManhours that need the database, checked before the task is assigned or completed so a refusal leaves it open
 * (after {@link TaskValidity}, which checks the list and the 500-character limit). In this order: every {@code cbsCodeRef} must be a CBS of the week's check, 400 "CBS is
 * not part of this review" (ASSUMPTION text); then every CBS with a man-hour alert in the period needs a non-blank remark, 400 "A remark is required for &lt;CBS&gt; (alert
 * raised)" (the story's text, FRD-22 §22.5.6.5), whether its entry is missing or blank. The record step checks the same again (the backstop).
 */
@Component
public class CbsReviewCheck {

    static final String ELEMENT = "UserTask_ReviewActualManhours";

    private final ProgressPeriodRepository periods;
    private final VarianceCheckService checks;
    private final CbsReviewService reviews;

    public CbsReviewCheck(ProgressPeriodRepository periods, VarianceCheckService checks, CbsReviewService reviews) {
        this.periods = periods;
        this.checks = checks;
        this.reviews = reviews;
    }

    boolean appliesTo(String elementId) {
        return ELEMENT.equals(elementId);
    }

    /** Why the completion is refused (status and text), or empty when it may go on. */
    Optional<HoursSegregationCheck.Refusal> refusal(long processInstanceKey, Map<String, Object> submitted) {
        Optional<Period> found = periods.ofInstance(processInstanceKey);
        if (found.isEmpty()) {
            return Optional.of(new HoursSegregationCheck.Refusal(HttpStatus.CONFLICT, "The week of this task has no progress period")); // ASSUMPTION text, as BPA-60
        }
        Period period = found.get();
        List<Review> entries;
        try {
            entries = CbsReviewService.parse(submitted.get("cbsReviews"));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
        Set<String> weekCbs = checks.checks(period.weekEnding()).stream().map(Check::cbsCodeRef).collect(Collectors.toSet());
        for (Review review : entries) {
            if (!weekCbs.contains(review.cbsCodeRef())) {
                return bad(CbsReviewService.NOT_OF_THE_WEEK);
            }
        }
        Map<String, Review> byCbs = new HashMap<>();
        entries.forEach(e -> byCbs.put(e.cbsCodeRef(), e));
        for (String cbs : reviews.alerted(period)) {
            Review review = byCbs.get(cbs);
            if (review == null || review.remark() == null || review.remark().isBlank()) {
                return bad(CbsReviewService.remarkRequired(cbs));
            }
        }
        return Optional.empty();
    }

    private static Optional<HoursSegregationCheck.Refusal> bad(String message) {
        return Optional.of(new HoursSegregationCheck.Refusal(HttpStatus.BAD_REQUEST, message));
    }
}
