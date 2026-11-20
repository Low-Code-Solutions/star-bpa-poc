package com.lcs.star.bpa.progress;

import com.lcs.star.bpa.persistence.ProgressPeriodRepository.Period;
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
    public record Approval(String workpackNumber, BigDecimal approvedPct, String remark) {
    }

    private final ProgressReadService read;
    private final WorkpackProgressRepository progress;

    public CompletionApprovalService(ProgressReadService read, WorkpackProgressRepository progress) {
        this.read = read;
        this.progress = progress;
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
                BigDecimal approved;
                try {
                    approved = pct instanceof Number || pct instanceof String s && !s.isBlank() ? new BigDecimal(String.valueOf(pct).trim()) : null;
                } catch (NumberFormatException e) {
                    approved = null;
                }
                if (approved == null) {
                    throw new IllegalArgumentException(NOT_A_NUMBER);
                }
                approvals.add(new Approval(number.trim(), approved, map.get("remark") instanceof String remark ? remark : null));
            }
        }
        return approvals;
    }

    /** Writes the approvals of the week; the Work Packs and what they are compared with come from the week's read model. */
    @Transactional
    public void record(Period period, List<Approval> approvals, String username) {
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
        }
    }
}
