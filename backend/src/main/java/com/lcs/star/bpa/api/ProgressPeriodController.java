package com.lcs.star.bpa.api;

import com.lcs.star.bpa.api.generated.ProgressPeriodsApi;
import com.lcs.star.bpa.api.generated.model.ProgressWeek;
import com.lcs.star.bpa.api.generated.model.WorkpackProgressRow;
import com.lcs.star.bpa.progress.ProgressReadService;
import com.lcs.star.bpa.progress.ProgressReadService.Row;
import com.lcs.star.bpa.progress.ProgressReadService.Week;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/progress-periods/{weekEnding}/workpacks} (BPA-60; E2-10): one row per Work Pack of the week for the Approve completion % screen
 * (ProgressReadService). SecurityConfig lets ppc-team and project-manager through (delta §7), 403 for the rest. The week must have a progress period,
 * else 404 "Week not found" (ASSUMPTION text).
 */
@RestController
public class ProgressPeriodController implements ProgressPeriodsApi {

    private final ProgressReadService service;

    public ProgressPeriodController(ProgressReadService service) {
        this.service = service;
    }

    @Override
    public ResponseEntity<ProgressWeek> getProgressWeekWorkpacks(LocalDate weekEnding) {
        Week week = service.week(weekEnding).orElseThrow(() -> new ErrorResponseException(HttpStatus.NOT_FOUND,
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Week not found"), null));
        List<WorkpackProgressRow> rows = week.rows().stream().map(ProgressPeriodController::map).toList();
        return ResponseEntity.ok(new ProgressWeek(week.weekEnding(), ProgressWeek.BasisEnum.fromValue(week.basis()), rows));
    }

    /** BPA-62 stub (contract first): answers 501 until the implementation commit, so the red tests compile and fail for the expected reason. */
    @Override
    public ResponseEntity<com.lcs.star.bpa.api.generated.model.EarnedManhoursWeek> getProgressWeekEarnedManhours(LocalDate weekEnding) {
        throw new ErrorResponseException(HttpStatus.NOT_IMPLEMENTED);
    }

    private static WorkpackProgressRow map(Row row) {
        WorkpackProgressRow out = new WorkpackProgressRow(row.workpackNumber(), row.noReportRow(), row.lastLockedPct(), row.approvedHours(), row.approvedPct());
        out.setReportedPct(row.reportedPct());
        out.setHoursBasedPct(row.hoursBasedPct());
        out.setReportedByProductionPct(row.reportedByProductionPct());
        out.setFmsActualHours(row.fmsActualHours());
        out.setHoursDifference(row.hoursDifference());
        out.setRemark(row.remark());
        return out;
    }
}
