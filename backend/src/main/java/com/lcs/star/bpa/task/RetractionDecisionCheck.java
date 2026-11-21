package com.lcs.star.bpa.task;

import com.lcs.star.bpa.persistence.ProgressPeriodRepository;
import com.lcs.star.bpa.persistence.ProgressPeriodRepository.Period;
import com.lcs.star.bpa.persistence.ProgressRetractionRepository.Request;
import com.lcs.star.bpa.progress.RetractionDecisionService;
import com.lcs.star.bpa.progress.RetractionDecisionService.Decision;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * BPA-63: the rules of completing UserTask_ApproveRetraction that need the database, checked before the task is assigned or completed so a refusal leaves it
 * open (after {@link TaskValidity}, which checks each entry's decision and reason). The decisions must cover every request that waits for one in the week, and
 * no other: 400 "Decisions must cover every Work Pack waiting for a retraction" (ASSUMPTION, plan Q7). Segregation (FRD-21 §21.8.1): a decision by the user who
 * requested a retraction is 409 "You cannot approve a retraction you requested" (Jira; plan Q4: any decision, the same rule as BPA-56). The record step
 * checks the same again (the backstop).
 */
@Component
public class RetractionDecisionCheck {

    static final String ELEMENT = "UserTask_ApproveRetraction";

    private final ProgressPeriodRepository periods;
    private final RetractionDecisionService service;

    public RetractionDecisionCheck(ProgressPeriodRepository periods, RetractionDecisionService service) {
        this.periods = periods;
        this.service = service;
    }

    boolean appliesTo(String elementId) {
        return ELEMENT.equals(elementId);
    }

    /** Why the completion is refused (status and text), or empty when it may go on. */
    Optional<HoursSegregationCheck.Refusal> refusal(long processInstanceKey, String username, Map<String, Object> submitted) {
        Optional<Period> found = periods.ofInstance(processInstanceKey);
        if (found.isEmpty()) {
            return Optional.of(new HoursSegregationCheck.Refusal(HttpStatus.CONFLICT, "The week of this task has no progress period")); // ASSUMPTION text, as BPA-60
        }
        List<Decision> decisions;
        try {
            decisions = RetractionDecisionService.parse(submitted.get("retractionDecisions"));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
        List<Request> open = service.open(found.get());
        Set<String> expected = open.stream().map(Request::workpackNumber).collect(Collectors.toSet());
        Set<String> named = new HashSet<>();
        decisions.forEach(d -> named.add(d.workpackNumber()));
        if (open.isEmpty() || named.size() != decisions.size() || !named.equals(expected)) {
            return bad(RetractionDecisionService.NOT_COVERED);
        }
        if (open.stream().anyMatch(r -> username.equals(r.requestedBy()))) {
            return Optional.of(new HoursSegregationCheck.Refusal(HttpStatus.CONFLICT, RetractionDecisionService.SEGREGATION));
        }
        return Optional.empty();
    }

    private static Optional<HoursSegregationCheck.Refusal> bad(String message) {
        return Optional.of(new HoursSegregationCheck.Refusal(HttpStatus.BAD_REQUEST, message));
    }
}
