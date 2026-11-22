package com.lcs.star.bpa.progress;

import com.lcs.star.bpa.persistence.CompletionReportRepository;
import com.lcs.star.bpa.persistence.CompletionReportRepository.ReadRow;
import com.lcs.star.bpa.persistence.ProgressPeriodRepository;
import com.lcs.star.bpa.persistence.WorkpackProgressRepository;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Read completion report step (BPA-59; plan section 5): decides what a report is worth and applies it. A report with no Read row is {@code Invalid}
 * (the job then throws REPORT_INVALID); otherwise it is {@code Accepted}: each Read row sets {@code workpack_progress.reported_pct} for the report's week
 * (SOR FMS) and a Work Pack of the week that is not in the report loses its reported % while it has no approved % (the latest accepted report wins,
 * Q-DM20). One transaction, locked on the report. Re-run key: the report's own outcome, which is decided once, so a retried job changes nothing.
 */
@Service
public class CompletionProgressService {

    public enum Decision { ACCEPTED, INVALID }

    private final CompletionReportRepository reports;
    private final WorkpackProgressRepository progress;
    private final ProgressPeriodRepository periods;

    public CompletionProgressService(CompletionReportRepository reports, WorkpackProgressRepository progress, ProgressPeriodRepository periods) {
        this.reports = reports;
        this.progress = progress;
        this.periods = periods;
    }

    @Transactional
    public Decision decide(UUID reportId, UUID periodId, String username) {
        String outcome = reports.lockedOutcome(reportId).orElse(null);
        if ("Accepted".equals(outcome)) {
            return Decision.ACCEPTED; // re-run: applied already
        }
        if ("Invalid".equals(outcome)) {
            return Decision.INVALID;
        }
        if (outcome != null) {
            // a file-level line ("File cannot be read", ...): the report has no row to read; the line stays as it is (QA review: GET result keeps its meaning)
            return Decision.INVALID;
        }
        periods.requireOpen(periodId); // BPA-67: a Locked week's reported % never changes (plan Q3)
        List<ReadRow> read = reports.readRows(reportId);
        if (read.isEmpty()) {
            reports.setOutcome(reportId, "Invalid", username);
            return Decision.INVALID;
        }
        Map<String, BigDecimal> reported = new LinkedHashMap<>();
        for (ReadRow row : read) {
            reported.put(row.workpackNumber(), CompletionRowChecks.number(row.completionRaw()).orElseThrow());
        }
        Collection<UUID> kept = progress.setReported(periodId, reported, username);
        progress.clearReportedExcept(periodId, kept, username);
        reports.setOutcome(reportId, "Accepted", username);
        return Decision.ACCEPTED;
    }
}
