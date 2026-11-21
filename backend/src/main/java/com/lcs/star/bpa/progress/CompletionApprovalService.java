package com.lcs.star.bpa.progress;

import com.lcs.star.bpa.persistence.ProgressPeriodRepository;
import com.lcs.star.bpa.persistence.ProgressPeriodRepository.Period;
import com.lcs.star.bpa.persistence.ProgressRetractionRepository;
import com.lcs.star.bpa.persistence.ProgressRetractionRepository.Request;
import com.lcs.star.bpa.persistence.WorkpackProgressRepository;
import com.lcs.star.bpa.progress.ProgressReadService.Row;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The approval of the week's completion % (BPA-60; E2-10; plan section 3). {@link #parse} reads the task's {@code completionApprovals} payload; {@link #record}
 * writes it, all or nothing for the week (decision (d)), in one transaction: for each Work Pack {@code approved_pct}, {@code override_remark} (only when the
 * approved % differs from the reported %, or from the last locked % when there is no report row), {@code previous_pct} (the last locked %), {@code approved_by}
 * and {@code approved_at}. A row that already holds the same values is left as it is, so a re-run keeps {@code approved_at} and audits nothing twice.
 */
@Service
public class CompletionApprovalService {

    public static final String NOT_A_NUMBER = "Completion % must be a number";
    public static final String NOT_COVERED = "Approvals must cover every Work Pack of the week";

    /** One entry of the payload: the Work Pack, the approved % as sent and the remark as sent (null when none). */
    public record Approval(String workpackNumber, BigDecimal approvedPct, String remark, String retractionReason) {
    }

    private final ProgressReadService read;
    private final WorkpackProgressRepository progress;
    private final ProgressPeriodRepository periods;
    private final ProgressRetractionRepository retractions;

    public CompletionApprovalService(ProgressReadService read, WorkpackProgressRepository progress, ProgressPeriodRepository periods, ProgressRetractionRepository retractions) {
        this.read = read;
        this.progress = progress;
        this.periods = periods;
        this.retractions = retractions;
    }

    /** The payload as entries; an entry that is not understood is an {@link IllegalArgumentException} with the text to answer with. */
    public static List<Approval> parse(Object payload) {
        List<Approval> approvals = new ArrayList<>();
        if (payload instanceof List<?> entries) {
            for (Object entry : entries) {
                if (!(entry instanceof Map<?, ?> map) || !(map.get("workpackNumber") instanceof String number) || number.isBlank()) {
                    throw new IllegalArgumentException(NOT_COVERED);
                }
                Object pct = map.get("approvedPct");
                // a plain decimal only: BigDecimal would read "1e1" as 10 (QA review)
                BigDecimal approved = pct instanceof Number ? new BigDecimal(String.valueOf(pct)) : pct instanceof String s ? CompletionRowChecks.number(s).orElse(null) : null;
                if (approved == null) {
                    throw new IllegalArgumentException(NOT_A_NUMBER);
                }
                approvals.add(new Approval(number.trim(), approved, map.get("remark") instanceof String remark ? remark : null, map.get("retractionReason") instanceof String reason ? reason : null));
            }
        }
        return approvals;
    }

    /**
     * BPA-63 (plan Q3): the retraction request of the Work Pack, written in the same transaction as the approval. Needs a reason (below last week's locked %, no
     * Approved retraction for this %): an open request is inserted, or updated to the new reason and %. Needs none: an open request goes (ASSUMPTION). Decided
     * requests are never touched, so a re-approval at a different % is a new request and an Approved one is kept (BPA-61 Q4).
     */
    private void request(Period period, Row row, BigDecimal approved, String reasonText, String username) {
        List<Request> mine = retractions.ofPeriod(period.id()).stream().filter(r -> r.workpackId().equals(row.workpackId())).toList();
        Request open = mine.stream().filter(Request::open).findFirst().orElse(null);
        if (!RetractionRules.needsReason(approved, row.lastLockedPct(), mine)) {
            if (open != null) {
                retractions.delete(open, username);
            }
            return;
        }
        String reason = reasonText == null ? "" : reasonText.trim();
        if (reason.isEmpty()) {
            throw new IllegalArgumentException(RetractionRules.reasonRequired(row.lastLockedPct()));
        }
        if (open != null) {
            retractions.update(open, reason, approved, username);
        } else {
            retractions.insert(retractions.progressId(period.id(), row.workpackId()).orElseThrow(), username, reason, approved, username);
        }
    }

    /** Writes the approvals of the week; the Work Packs and what they are compared with come from the week's read model. */
    @Transactional
    public void record(Period period, List<Approval> approvals, String username) {
        // rows of a Locked week never change (data model 13.3.4, worker-checked; QA review): a stale instance cannot overwrite them
        if (periods.statusOfWeek(period.weekEnding()).filter("Locked"::equals).isPresent()) {
            throw new IllegalArgumentException("Week ending " + period.weekEnding().format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH)) + " is locked");
        }
        Map<String, Row> rows = new HashMap<>();
        read.week(period).rows().forEach(row -> rows.put(row.workpackNumber(), row));
        for (Approval approval : approvals) {
            Row row = rows.get(approval.workpackNumber());
            if (row == null) {
                throw new IllegalArgumentException(NOT_COVERED);
            }
            BigDecimal approved = approval.approvedPct().setScale(2, RoundingMode.HALF_UP);
            boolean changed = approved.compareTo(row.referencePct()) != 0;
            String remark = changed && approval.remark() != null && !approval.remark().isBlank() ? approval.remark().trim() : null;
            progress.approve(period.id(), row.workpackId(), approved, remark, row.lastLockedPct(), username);
            request(period, row, approved, approval.retractionReason(), username);
        }
    }
}
