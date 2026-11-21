package com.lcs.star.bpa.progress;

import com.lcs.star.bpa.persistence.CbsReviewRepository;
import com.lcs.star.bpa.persistence.ProgressPeriodRepository;
import com.lcs.star.bpa.persistence.ProgressPeriodRepository.Period;
import com.lcs.star.bpa.persistence.VarianceAlertRepository;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Cost Controller's review of the week's CBS (BPA-64; E2-14; plan section 4). {@link #parse} reads the task's {@code cbsReviews} payload, {@link #record} writes
 * it in one transaction: one {@code cbs_review} row per CBS with the remark (blank is stored as NULL), {@code reviewed_by} = {@code completedBy} and
 * {@code reviewed_at}; a review sent back by the Project Manager (BPA-66) updates the row. A remark is required for every CBS with a Manhour alert
 * (FRD-22 §22.5.6.5): the task API checks it first, and the record step checks it again (the backstop). A Locked week is refused.
 */
@Service
public class CbsReviewService {

    /** The story's text. */
    public static String remarkRequired(String cbs) {
        return "A remark is required for " + cbs + " (alert raised)";
    }

    /** ASSUMPTION texts (plan Q12). */
    public static final String NOT_AN_ENTRY = "Reviews are required";
    public static final String REMARK_TOO_LONG = "Remark can be at most 500 characters";
    public static final String NOT_OF_THE_WEEK = "CBS is not part of this review";

    /** One entry of the payload: the CBS and the remark as sent (null when none). */
    public record Review(String cbsCodeRef, String remark) {
        String trimmed() {
            return remark == null || remark.isBlank() ? null : remark.trim();
        }
    }

    private final CbsReviewRepository reviews;
    private final VarianceAlertRepository alerts;
    private final ProgressPeriodRepository periods;

    public CbsReviewService(CbsReviewRepository reviews, VarianceAlertRepository alerts, ProgressPeriodRepository periods) {
        this.reviews = reviews;
        this.alerts = alerts;
        this.periods = periods;
    }

    /** The payload as entries; an entry that is not understood is an {@link IllegalArgumentException} with the text to answer with. */
    public static List<Review> parse(Object payload) {
        List<Review> parsed = new ArrayList<>();
        if (payload instanceof List<?> entries) {
            for (Object entry : entries) {
                if (!(entry instanceof Map<?, ?> map) || !(map.get("cbsCodeRef") instanceof String cbs) || cbs.isBlank()) {
                    throw new IllegalArgumentException(NOT_AN_ENTRY);
                }
                parsed.add(new Review(cbs.trim(), map.get("remark") instanceof String remark ? remark : null));
            }
        }
        return parsed;
    }

    /** The CBS of the period that raised a Manhour or a Cost alert (BPA-65: the remark rule covers cost alerts too, agreed 10 Nov 2026) and so need a remark, in CBS order. */
    public Set<String> alerted(Period period) {
        Set<String> alerted = new TreeSet<>(alerts.alertedCbs(period.id(), VarianceAlertRepository.MANHOUR));
        alerted.addAll(alerts.alertedCbs(period.id(), VarianceAlertRepository.COST));
        return alerted;
    }

    @Transactional
    public void record(Period period, List<Review> entries, String username) {
        if (periods.statusOfWeek(period.weekEnding()).filter("Locked"::equals).isPresent()) {
            throw new IllegalArgumentException("Week ending " + period.weekEnding().format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)) + " is locked");
        }
        Map<String, Review> byCbs = new java.util.LinkedHashMap<>();
        entries.forEach(e -> byCbs.put(e.cbsCodeRef(), e));
        for (String cbs : alerted(period)) {
            Review review = byCbs.get(cbs);
            if (review == null || review.trimmed() == null) {
                throw new IllegalArgumentException(remarkRequired(cbs));
            }
        }
        for (Review review : entries) {
            if (review.remark() != null && review.remark().trim().length() > 500) {
                throw new IllegalArgumentException(REMARK_TOO_LONG);
            }
            reviews.upsert(period.id(), review.cbsCodeRef(), review.trimmed(), username);
        }
    }
}
