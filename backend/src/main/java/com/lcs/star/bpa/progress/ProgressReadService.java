package com.lcs.star.bpa.progress;

import com.lcs.star.bpa.config.ProgressSettings;
import com.lcs.star.bpa.persistence.ProgressPeriodRepository;
import com.lcs.star.bpa.persistence.ProgressPeriodRepository.Period;
import com.lcs.star.bpa.persistence.ProgressRetractionRepository;
import com.lcs.star.bpa.persistence.ProgressRetractionRepository.Request;
import com.lcs.star.bpa.persistence.ProgressWeekRepository;
import com.lcs.star.bpa.persistence.ProgressWeekRepository.Raw;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The read model of the Approve completion % screen (BPA-60; E2-10), one row per Work Pack of the week. Pure arithmetic on what
 * {@link ProgressWeekRepository} reads; nothing is stored here. Rules (decisions D6, (c); plan Q1, Q9, Q10, Q12):
 * <ul>
 * <li>No report row: no {@code reported_pct} for the week; the row starts from the last locked % (0 when never locked), D6.</li>
 * <li>Hours-based % = Approved hours up to the week ending ÷ the Work Pack's budget (the sum of its phase man-hours) × 100, 2 decimals half-up (Q-DM18 style);
 * absent when the budget is 0 (ASSUMPTION).</li>
 * <li>FMS actual hours = the Actual hours of the accepted report's row, as a number; the difference is FMS minus the POC's Approved hours.</li>
 * <li>The approved % to pre-fill: a stored approval wins (the task came back, Q9); otherwise by {@code progress.basis}: Physical = the reported %, Hours = the
 * hours-based % (the reported % when it cannot be worked out, ASSUMPTION); with no report row the last locked %.</li>
 * <li>{@code referencePct} is what a changed approved % is compared with for the remark: the reported %, or the last locked % when there is no report row.</li>
 * </ul>
 */
@Service
public class ProgressReadService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** One row of the week. */
    public record Row(String workpackNumber, UUID workpackId, boolean noReportRow, BigDecimal reportedPct, BigDecimal lastLockedPct, BigDecimal hoursBasedPct,
            BigDecimal reportedByProductionPct, BigDecimal fmsActualHours, BigDecimal approvedHours, BigDecimal hoursDifference, BigDecimal approvedPct,
            BigDecimal referencePct, String remark, String retractionReason, String retractionRequestedBy, String retractionDeclinedReason, boolean retractionApproved,
            List<ProgressWeekRepository.Unsent> unsentLines) {
    }

    /** The week: its period, the basis in force and the rows. */
    public record Week(UUID periodId, LocalDate weekEnding, String basis, List<Row> rows) {
    }

    private final ProgressPeriodRepository periods;
    private final ProgressWeekRepository weeks;
    private final ProgressSettings settings;
    private final ProgressRetractionRepository retractions;

    public ProgressReadService(ProgressPeriodRepository periods, ProgressWeekRepository weeks, ProgressSettings settings, ProgressRetractionRepository retractions) {
        this.periods = periods;
        this.weeks = weeks;
        this.settings = settings;
        this.retractions = retractions;
    }

    /** The week ending's rows, or empty when the week has no progress period. */
    public Optional<Week> week(LocalDate weekEnding) {
        return periods.ofWeek(weekEnding).map(this::week);
    }

    public Week week(Period period) {
        String basis = settings.basis();
        List<Request> requests = retractions.ofPeriod(period.id());
        List<Row> rows = weeks.workpacks(period.id(), period.weekEnding()).stream().map(raw -> row(raw, basis, requests, weeks)).toList();
        return new Week(period.id(), period.weekEnding(), basis, rows);
    }

    private static Row row(Raw raw, String basis, List<Request> requests, ProgressWeekRepository weeks) {
        boolean noReportRow = raw.reportedPct() == null;
        BigDecimal reported = scale(raw.reportedPct());
        BigDecimal lastLocked = scale(raw.lastLockedPct());
        BigDecimal approvedHours = scale(raw.approvedHours());
        BigDecimal hoursBased = raw.budget() == null || raw.budget().signum() <= 0 ? null
                : approvedHours.multiply(HUNDRED).divide(raw.budget(), 2, RoundingMode.HALF_UP);
        BigDecimal fms = number(raw.fmsActualHoursRaw());
        BigDecimal difference = fms == null ? null : fms.subtract(approvedHours).setScale(2, RoundingMode.HALF_UP);
        BigDecimal reference = noReportRow ? lastLocked : reported;
        BigDecimal prefilled;
        if (raw.storedApprovedPct() != null) {
            prefilled = scale(raw.storedApprovedPct());
        } else if (noReportRow) {
            prefilled = lastLocked;
        } else if ("Hours".equals(basis) && hoursBased != null) {
            prefilled = hoursBased;
        } else {
            prefilled = reported;
        }
        List<Request> mine = requests.stream().filter(r -> r.workpackId().equals(raw.workpackId())).toList();
        Request open = mine.stream().filter(Request::open).findFirst().orElse(null);
        boolean approvedHere = mine.stream().anyMatch(r -> "Approved".equals(r.decision()) && r.approvedPct().compareTo(prefilled) == 0);
        // BPA-63 (plan Q5): the latest Project Manager decline for the % now approved; it goes once PPC changes the % or a new request is open
        Request declined = open != null ? null
                : mine.stream().filter(r -> "Declined".equals(r.decision()) && r.approvedPct().compareTo(prefilled) == 0).reduce((first, last) -> last).orElse(null);
        return new Row(raw.workpackNumber(), raw.workpackId(), noReportRow, reported, lastLocked, hoursBased, scale(raw.reportedByProduction()), fms, approvedHours,
                difference, prefilled, reference, raw.storedRemark(), open == null ? null : open.reason(), open == null ? null : open.requestedBy(),
                declined == null ? null : declined.decisionReason(), approvedHere, unsent(raw, prefilled, weeks));
    }

    /** BPA-66 (BPA-67 rule (c)): the Draft and Returned lines of a Work Pack approved at exactly 100%, so the Project Manager sees them before approving the week; none for any other %. */
    private static List<ProgressWeekRepository.Unsent> unsent(Raw raw, BigDecimal approvedPct, ProgressWeekRepository weeks) {
        return approvedPct != null && approvedPct.compareTo(HUNDRED) == 0 ? weeks.unsentLines(raw.workpackId()) : List.of();
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    /** The report's Actual hours as written, as a number with 2 decimals; null when empty or not a number. */
    private static BigDecimal number(String text) {
        return text == null ? null : CompletionRowChecks.number(text).map(BigDecimal::stripTrailingZeros).map(n -> n.setScale(2, RoundingMode.HALF_UP)).orElse(null);
    }
}
