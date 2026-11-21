package com.lcs.star.bpa.progress;

import com.lcs.star.bpa.persistence.ProgressPeriodRepository;
import com.lcs.star.bpa.persistence.ProgressPeriodRepository.Period;
import com.lcs.star.bpa.persistence.ProgressRetractionRepository;
import com.lcs.star.bpa.persistence.ProgressRetractionRepository.Request;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Project Manager's decisions on the week's retractions (BPA-63; E2-12; plan section 3). {@link #parse} reads the task's {@code retractionDecisions}
 * payload; {@link #decide} writes it in one transaction: on every open request of the week {@code decision}, {@code decision_reason} (only for a decline),
 * {@code decided_by}, {@code decided_at} and {@code task_key}, with one {@code audit_log} row per column. The outcome is "Approved" only when every
 * Work Pack is Approved, else "Declined" (plan Q2). Re-run key: the task key (UNIQUE with the Work Pack row, V4): rows that already carry it are left as
 * they are and the outcome is read from them. The same checks as the task API are made again here (the backstop, as BPA-56).
 */
@Service
public class RetractionDecisionService {

    public static final String SEGREGATION = "You cannot approve a retraction you requested";
    /** ASSUMPTION texts (plan Q7). */
    public static final String NOT_COVERED = "Decisions must cover every Work Pack waiting for a retraction";
    public static final String DECISION_UNKNOWN = "Decision must be Approved or Declined";
    public static final String REASON_REQUIRED = "Reason is required"; // the story's text
    public static final String REASON_TOO_LONG = "Reason can be at most 250 characters";

    /** One entry of the payload: the Work Pack, the decision as sent and the reason as sent (null when none). */
    public record Decision(String workpackNumber, String decision, String reason) {
    }

    private final ProgressPeriodRepository periods;
    private final ProgressRetractionRepository retractions;

    public RetractionDecisionService(ProgressPeriodRepository periods, ProgressRetractionRepository retractions) {
        this.periods = periods;
        this.retractions = retractions;
    }

    /** The payload as entries; an entry that is not understood is an {@link IllegalArgumentException} with the text to answer with. */
    public static List<Decision> parse(Object payload) {
        List<Decision> decisions = new ArrayList<>();
        if (payload instanceof List<?> entries) {
            for (Object entry : entries) {
                if (!(entry instanceof Map<?, ?> map) || !(map.get("workpackNumber") instanceof String number) || number.isBlank()) {
                    throw new IllegalArgumentException(NOT_COVERED);
                }
                decisions.add(new Decision(number.trim(), map.get("retractionDecision") instanceof String d ? d : null, map.get("reason") instanceof String r ? r : null));
            }
        }
        return decisions;
    }

    /** The requests that wait for a decision in the week, oldest first. */
    public List<Request> open(Period period) {
        return retractions.ofPeriod(period.id()).stream().filter(Request::open).toList();
    }

    /** Writes the decisions and returns the outcome, "Approved" or "Declined". Anything the checks refuse is an {@link IllegalArgumentException}. */
    @Transactional
    public String decide(Period period, List<Decision> decisions, String username, long taskKey) {
        if (periods.statusOfWeek(period.weekEnding()).filter("Locked"::equals).isPresent()) {
            throw new IllegalArgumentException("Week ending " + period.weekEnding().format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)) + " is locked");
        }
        List<Request> all = retractions.ofPeriod(period.id());
        List<Request> already = all.stream().filter(r -> r.taskKey() != null && r.taskKey() == taskKey).toList();
        if (!already.isEmpty()) {
            return outcome(already.stream().map(r -> "Approved".equals(r.decision())).toList()); // a re-run: nothing is written again
        }
        List<Request> open = all.stream().filter(Request::open).toList();
        Set<String> expected = open.stream().map(Request::workpackNumber).collect(Collectors.toSet());
        Set<String> named = new HashSet<>();
        decisions.forEach(d -> named.add(d.workpackNumber()));
        if (open.isEmpty() || named.size() != decisions.size() || !named.equals(expected)) {
            throw new IllegalArgumentException(NOT_COVERED);
        }
        for (Decision d : decisions) {
            if (!"Approved".equals(d.decision()) && !"Declined".equals(d.decision())) {
                throw new IllegalArgumentException(DECISION_UNKNOWN);
            }
            if ("Declined".equals(d.decision())) {
                if (d.reason() == null || d.reason().isBlank()) {
                    throw new IllegalArgumentException(REASON_REQUIRED);
                }
                if (d.reason().trim().length() > 250) {
                    throw new IllegalArgumentException(REASON_TOO_LONG);
                }
            }
        }
        if (open.stream().anyMatch(r -> username.equals(r.requestedBy()))) {
            throw new IllegalArgumentException(SEGREGATION);
        }
        Map<String, Decision> byNumber = decisions.stream().collect(Collectors.toMap(Decision::workpackNumber, d -> d));
        List<Boolean> approved = new ArrayList<>();
        for (Request request : open) {
            Decision d = byNumber.get(request.workpackNumber());
            boolean yes = "Approved".equals(d.decision());
            retractions.decide(request, d.decision(), yes ? null : d.reason().trim(), username, taskKey);
            approved.add(yes);
        }
        return outcome(approved);
    }

    private static String outcome(List<Boolean> approved) {
        return approved.stream().allMatch(Boolean::booleanValue) ? "Approved" : "Declined";
    }
}
