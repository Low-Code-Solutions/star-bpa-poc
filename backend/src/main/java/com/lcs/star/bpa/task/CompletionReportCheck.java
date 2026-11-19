package com.lcs.star.bpa.task;

import com.lcs.star.bpa.persistence.CompletionReportRepository;
import com.lcs.star.bpa.persistence.ProgressPeriodRepository;
import com.lcs.star.bpa.persistence.ProgressPeriodRepository.Period;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * BPA-59: the Upload completion report completion must name a report of this task's own week, so a report uploaded into another week's task cannot be
 * applied from this one. "Completion report is not for this week" is an ASSUMPTION text (plan Q9); {@link TaskValidity} has checked that an id is there.
 */
@Component
public class CompletionReportCheck {

    static final String ELEMENT = "UserTask_UploadCompletionReport";
    static final String NOT_FOR_THIS_WEEK = "Completion report is not for this week";

    private final CompletionReportRepository reports;
    private final ProgressPeriodRepository periods;

    public CompletionReportCheck(CompletionReportRepository reports, ProgressPeriodRepository periods) {
        this.reports = reports;
        this.periods = periods;
    }

    boolean appliesTo(String elementId) {
        return ELEMENT.equals(elementId);
    }

    /** The message to answer with (400), or empty when the report belongs to the week of the task's instance. */
    Optional<String> refusal(long processInstanceKey, Map<String, Object> submitted) {
        UUID report;
        try {
            report = UUID.fromString(String.valueOf(submitted.get("completionReportId")).trim());
        } catch (IllegalArgumentException e) {
            return Optional.of(NOT_FOR_THIS_WEEK);
        }
        Optional<UUID> reportPeriod = reports.periodOf(report);
        Optional<UUID> taskPeriod = periods.ofInstance(processInstanceKey).map(Period::id);
        return reportPeriod.isPresent() && reportPeriod.equals(taskPeriod) ? Optional.empty() : Optional.of(NOT_FOR_THIS_WEEK);
    }
}
