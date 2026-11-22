package com.lcs.star.bpa.progress;

import com.lcs.star.bpa.persistence.ProgressPeriodRepository;
import com.lcs.star.bpa.persistence.ProgressPeriodRepository.Period;
import com.lcs.star.bpa.persistence.ProgressWeekRepository;
import com.lcs.star.bpa.persistence.ProgressWeekRepository.Approved;
import com.lcs.star.bpa.persistence.WorkpackProgressRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The progress rule around Decision_ProgressRule (BPA-61; E2-11; plan sections 1 and 2). {@link #checks} builds {@code progressChecks} for the week, one per
 * Work Pack that PPC approved a % for: {@code newPct}, {@code previousPct} (the last locked week's approved %, 0 when none) and {@code retractionApproved}
 * (true only when an Approved retraction exists for the Work Pack and week whose % equals {@code newPct}, decision (a)). It only reads, so a retried job gives
 * the same list. {@link #record} writes the decision's results to {@code workpack_progress.rule_result}, all in one transaction, and gives the messages the
 * returning task shows ({@link ProgressMessages}), one per Work Pack that is not Accepted; the same results written again change nothing.
 */
@Service
public class ProgressCheckService {

    /** One result of the rule for a Work Pack, as the rule task leaves it in {@code progressCheckResults}. */
    public record Result(String workpackNumber, BigDecimal newPct, BigDecimal previousPct, String progressResult, String progressReason) {
    }

    private final ProgressPeriodRepository periods;
    private final ProgressWeekRepository weeks;
    private final WorkpackProgressRepository progress;

    public ProgressCheckService(ProgressPeriodRepository periods, ProgressWeekRepository weeks, WorkpackProgressRepository progress) {
        this.periods = periods;
        this.weeks = weeks;
        this.progress = progress;
    }

    /**
     * The week's period: the instance's own (the process instance key, always the case in production), else the {@code progressPeriodId} variable the weekly
     * process carries from open-progress-period (so an instance started in the middle of the process, as the Blocked tests do, finds it; ASSUMPTION).
     */
    public Optional<Period> periodOf(long processInstanceKey, Object progressPeriodId) {
        Optional<Period> own = periods.ofInstance(processInstanceKey);
        if (own.isPresent()) {
            return own;
        }
        try {
            return progressPeriodId instanceof String id && !id.isBlank() ? periods.find(UUID.fromString(id)) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** The checks of the week, by Work Pack number; empty when no Work Pack has an approved %. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> checks(Period period) {
        List<Map<String, Object>> checks = new ArrayList<>();
        for (Approved row : weeks.approvedRows(period.id())) {
            Map<String, Object> check = new LinkedHashMap<>();
            check.put("workpackNumber", row.workpackNumber());
            check.put("newPct", row.approvedPct());
            check.put("previousPct", weeks.lastLockedPct(row.workpackId(), period.weekEnding()));
            check.put("retractionApproved", weeks.retractionApprovedFor(row.progressId(), row.approvedPct()));
            checks.add(check);
        }
        return checks;
    }

    /** The rule task's {@code progressCheckResults} as results; an entry that is not understood is an {@link IllegalArgumentException}. */
    public static List<Result> parse(Object payload) {
        List<Result> results = new ArrayList<>();
        if (payload instanceof List<?> entries) {
            for (Object entry : entries) {
                if (!(entry instanceof Map<?, ?> map) || !(map.get("workpackNumber") instanceof String number) || !(map.get("progressResult") instanceof String result)) {
                    throw new IllegalArgumentException("A progress check result has no Work Pack or result");
                }
                results.add(new Result(number, decimal(map.get("newPct")), decimal(map.get("previousPct")), result,
                        map.get("progressReason") instanceof String reason ? reason : null));
            }
        }
        return results;
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? null : new BigDecimal(String.valueOf(value));
    }

    /** Writes each result's {@code rule_result} and returns the messages (one per Work Pack that is not Accepted), in the order of the results. */
    @Transactional
    public List<String> record(Period period, List<Result> results, String username) {
        periods.requireOpen(period); // BPA-67: a Locked week's rule results never change (plan Q3)
        List<String> messages = new ArrayList<>();
        for (Result result : results) {
            progress.setRuleResult(period.id(), result.workpackNumber(), result.progressResult(), username);
            ProgressMessages.of(result.workpackNumber(), result.progressReason(), result.newPct(), result.previousPct()).ifPresent(messages::add);
        }
        return messages;
    }
}
