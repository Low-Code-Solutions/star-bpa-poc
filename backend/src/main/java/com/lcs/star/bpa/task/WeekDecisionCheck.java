package com.lcs.star.bpa.task;

import com.lcs.star.bpa.persistence.ProgressPeriodRepository;
import com.lcs.star.bpa.persistence.ProgressPeriodRepository.Period;
import com.lcs.star.bpa.persistence.ProgressWeekRepository;
import com.lcs.star.bpa.progress.WeekDecisionService;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * BPA-66: the rule of completing UserTask_ApproveWeeklyProgress that needs the database, checked before the task is assigned or completed so a refusal leaves it open
 * (after {@link TaskValidity}, which checks the decision, the target and the reason). Segregation (FRD-21 §21.8.1): approving a week whose Work Pack % the user approved
 * is 409 "You cannot approve a week whose progress you approved" (Jira). Only Approved is refused: a send-back by that user is allowed (ASSUMPTION, plan Q8). The record step
 * checks the same again (the backstop).
 */
@Component
public class WeekDecisionCheck {

    static final String ELEMENT = "UserTask_ApproveWeeklyProgress";

    private final ProgressPeriodRepository periods;
    private final ProgressWeekRepository weeks;

    public WeekDecisionCheck(ProgressPeriodRepository periods, ProgressWeekRepository weeks) {
        this.periods = periods;
        this.weeks = weeks;
    }

    boolean appliesTo(String elementId) {
        return ELEMENT.equals(elementId);
    }

    /** Why the completion is refused (status and text), or empty when it may go on. */
    Optional<HoursSegregationCheck.Refusal> refusal(long processInstanceKey, String username, Map<String, Object> submitted) {
        Optional<Period> found = periods.ofInstance(processInstanceKey);
        if (found.isEmpty()) {
            return Optional.of(new HoursSegregationCheck.Refusal(HttpStatus.CONFLICT, "The week of this task has no progress period")); // ASSUMPTION text, as BPA-60 and BPA-63
        }
        if ("Approved".equals(submitted.get("weekDecision")) && weeks.approvedBy(found.get().id(), username)) {
            return Optional.of(new HoursSegregationCheck.Refusal(HttpStatus.CONFLICT, WeekDecisionService.SEGREGATION));
        }
        return Optional.empty();
    }
}
