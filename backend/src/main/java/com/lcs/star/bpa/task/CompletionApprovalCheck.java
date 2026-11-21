package com.lcs.star.bpa.task;

import com.lcs.star.bpa.persistence.ProgressPeriodRepository;
import com.lcs.star.bpa.persistence.ProgressPeriodRepository.Period;
import com.lcs.star.bpa.persistence.ProgressRetractionRepository;
import com.lcs.star.bpa.persistence.ProgressRetractionRepository.Request;
import com.lcs.star.bpa.progress.CompletionApprovalService;
import com.lcs.star.bpa.progress.CompletionApprovalService.Approval;
import com.lcs.star.bpa.progress.ProgressReadService;
import com.lcs.star.bpa.progress.ProgressReadService.Row;
import com.lcs.star.bpa.progress.RetractionRules;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * BPA-60: the rules of completing UserTask_ApproveCompletion that need the database, checked before the task is assigned or completed so a refusal leaves it
 * open. In this order (plan section 2): the week before must be Locked, 409 "Week ending &lt;d MMM yyyy&gt; must be locked first" (Jira; with no earlier
 * period the rule is skipped, plan Q2); the approvals must cover every Work Pack of the week and no other; at most 2 decimals (Jira); between 0 and 100
 * (ASSUMPTION, Q8: the column's CHECK, so BPA-61's Blocked rows stay a backstop); a remark when the approved % differs from the reported % (Jira; from the last
 * locked % for a Work Pack with no report row, Q9); a remark of at most 250 characters (ASSUMPTION text). The texts not in Jira are plan Q11's.
 */
@Component
public class CompletionApprovalCheck {

    static final String ELEMENT = "UserTask_ApproveCompletion";
    static final String DECIMALS = "Completion % can have at most 2 decimal places";
    static final String RANGE = "Completion % must be between 0 and 100";
    static final String REMARK_REQUIRED = "A remark is required when the approved % differs from the reported %";
    static final String REMARK_TOO_LONG = "Remark can be at most 250 characters";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final ProgressPeriodRepository periods;
    private final ProgressReadService read;
    private final ProgressRetractionRepository retractions;

    public CompletionApprovalCheck(ProgressPeriodRepository periods, ProgressReadService read, ProgressRetractionRepository retractions) {
        this.periods = periods;
        this.read = read;
        this.retractions = retractions;
    }

    boolean appliesTo(String elementId) {
        return ELEMENT.equals(elementId);
    }

    /** Why the completion is refused (status and text), or empty when it may go on. */
    Optional<HoursSegregationCheck.Refusal> refusal(long processInstanceKey, Map<String, Object> submitted) {
        Optional<Period> found = periods.ofInstance(processInstanceKey);
        if (found.isEmpty()) {
            return Optional.of(new HoursSegregationCheck.Refusal(HttpStatus.CONFLICT, "The week of this task has no progress period")); // ASSUMPTION text
        }
        Period period = found.get();
        Optional<String> before = periods.statusOfWeek(period.weekEnding().minusDays(7));
        if (before.isPresent() && !"Locked".equals(before.get())) {
            return Optional.of(new HoursSegregationCheck.Refusal(HttpStatus.CONFLICT,
                    "Week ending " + period.weekEnding().minusDays(7).format(DATE) + " must be locked first"));
        }
        List<Approval> approvals;
        try {
            approvals = CompletionApprovalService.parse(submitted.get("completionApprovals"));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
        List<Row> rows = read.week(period).rows();
        Set<String> expected = rows.stream().map(Row::workpackNumber).collect(Collectors.toSet());
        Set<String> named = new HashSet<>();
        approvals.forEach(a -> named.add(a.workpackNumber()));
        if (named.size() != approvals.size() || !named.equals(expected)) {
            return bad(CompletionApprovalService.NOT_COVERED);
        }
        for (Approval a : approvals) {
            if (a.approvedPct().stripTrailingZeros().scale() > 2) {
                return bad(DECIMALS);
            }
        }
        for (Approval a : approvals) {
            if (a.approvedPct().signum() < 0 || a.approvedPct().compareTo(new BigDecimal("100")) > 0) {
                return bad(RANGE);
            }
        }
        Map<String, Row> byNumber = rows.stream().collect(Collectors.toMap(Row::workpackNumber, r -> r));
        for (Approval a : approvals) {
            boolean changed = a.approvedPct().compareTo(byNumber.get(a.workpackNumber()).referencePct()) != 0;
            if (changed && (a.remark() == null || a.remark().isBlank())) {
                return bad(REMARK_REQUIRED);
            }
        }
        for (Approval a : approvals) {
            if (a.remark() != null && a.remark().trim().length() > 250) {
                return bad(REMARK_TOO_LONG);
            }
        }
        // BPA-63 (plan section 1): a retraction reason where a Work Pack falls below last week's locked % and no Approved retraction holds for this % (Jira text);
        // a reason is at most 250 characters (ASSUMPTION text, plan Q7)
        Map<String, List<Request>> requests = retractions.ofPeriod(period.id()).stream().collect(Collectors.groupingBy(Request::workpackNumber));
        for (Approval a : approvals) {
            Row row = byNumber.get(a.workpackNumber());
            boolean blank = a.retractionReason() == null || a.retractionReason().isBlank();
            if (RetractionRules.needsReason(a.approvedPct(), row.lastLockedPct(), requests.getOrDefault(a.workpackNumber(), List.of())) && blank) {
                return bad(RetractionRules.reasonRequired(row.lastLockedPct()));
            }
        }
        for (Approval a : approvals) {
            if (a.retractionReason() != null && a.retractionReason().trim().length() > 250) {
                return bad(RetractionRules.REASON_TOO_LONG);
            }
        }
        return Optional.empty();
    }

    private static Optional<HoursSegregationCheck.Refusal> bad(String message) {
        return Optional.of(new HoursSegregationCheck.Refusal(HttpStatus.BAD_REQUEST, message));
    }
}
