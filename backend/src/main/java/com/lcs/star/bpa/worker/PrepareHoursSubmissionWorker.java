package com.lcs.star.bpa.worker;

import com.lcs.star.bpa.domain.HourLineChecks;
import com.lcs.star.bpa.persistence.DigitalTimesheetLineRepository;
import com.lcs.star.bpa.persistence.DigitalTimesheetLineRepository.SendLine;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Job type {@code prepare-hours-submission} on {@code ServiceTask_PrepareHoursSubmission} (BPA-51; E2-04, E2-05; 09 S7, ADR 0007). Runs when the
 * Supervisor completes UserTask_BookHours ("Send to Timekeeper"): collects the Work Pack's Draft and Returned lines and builds {@code lineChecks}, one
 * element per line, for BusinessRuleTask_CheckHoursWithinBudget, which re-checks the budget with Decision_HoursWithinBudget (the same decision as at
 * save). Each element carries workDate, phase, badgeNo, phaseBudgetHours, phaseHoursAfter and dailyHoursAfter (the totals of lines not Rejected, which
 * already include the saved line), dailyHoursLimit (app.daily-hours-limit), cbsCodeRef, cbsClosed and workerOnLeave (false until BPA-69 and BPA-75), and
 * the two message texts a Blocked reason maps to (the model copies the right one into lineCheckResults). With no Draft or Returned line it throws the
 * BPMN error HOURS_INVALID with validationMessages ("No Draft or Returned hours to send for WP-…": the text is the BPMN documentation's example, an
 * ASSUMPTION), which returns the instance to Book hours. It writes nothing, so a retried job builds the same checks; it clears validationMessages so
 * an earlier send's messages do not stay. Retries and backoff are Camunda's defaults (S7).
 */
@Component
public class PrepareHoursSubmissionWorker {

    private static final Logger LOG = LoggerFactory.getLogger(PrepareHoursSubmissionWorker.class);

    private final DigitalTimesheetLineRepository lines;
    private final BigDecimal dailyHoursLimit;

    public PrepareHoursSubmissionWorker(DigitalTimesheetLineRepository lines, @Value("${app.daily-hours-limit}") BigDecimal dailyHoursLimit) {
        this.lines = lines;
        this.dailyHoursLimit = dailyHoursLimit;
    }

    @JobWorker(type = "prepare-hours-submission", autoComplete = false)
    public void handle(JobClient client, ActivatedJob job) {
        Map<String, Object> variables = job.getVariablesAsMap();
        String workpackNumber = String.valueOf(variables.get("workpackNumber"));
        UUID workpackId = UUID.fromString(String.valueOf(variables.get("workpackId")));
        List<SendLine> sendable = lines.sendable(workpackId);
        if (sendable.isEmpty()) {
            LOG.info("prepare-hours-submission job {}: nothing to send for {}", job.getKey(), workpackNumber);
            client.newThrowErrorCommand(job.getKey()).errorCode("HOURS_INVALID")
                    .variables(Map.of("validationMessages", List.of("No Draft or Returned hours to send for " + workpackNumber))).send().join();
            return;
        }
        // BPA-67 (S5; ASSUMPTION, plan section 4): a line dated in a locked month cannot be sent; the task returns to Book hours with the message
        for (SendLine line : sendable) {
            if (lines.monthLocked(line.workDate())) {
                client.newThrowErrorCommand(job.getKey()).errorCode("HOURS_INVALID")
                        .variables(Map.of("validationMessages", List.of(HourLineChecks.monthLockedMessage(line.workDate())))).send().join();
                return;
            }
        }
        List<Map<String, Object>> checks = new ArrayList<>();
        for (SendLine line : sendable) {
            BigDecimal budget = lines.phaseBudget(workpackId, line.phase()).orElse(BigDecimal.ZERO);
            Map<String, Object> check = new LinkedHashMap<>();
            check.put("workDate", line.workDate().toString());
            check.put("phase", line.phase());
            check.put("badgeNo", line.badgeNo());
            check.put("phaseBudgetHours", budget);
            check.put("phaseHoursAfter", lines.phaseHours(workpackId, line.phase(), null));
            check.put("dailyHoursAfter", lines.workerHoursOn(line.workerId(), line.workDate(), null));
            check.put("dailyHoursLimit", dailyHoursLimit);
            check.put("cbsCodeRef", line.cbsCodeRef()); // null when the time sheet has no CBS (D40); BPA-69 reads cbs_block with it
            check.put("cbsClosed", false);
            check.put("workerOnLeave", false);
            check.put("dailyLimitMessage", HourLineChecks.dailyLimitMessage(line.badgeNo(), line.workDate(), dailyHoursLimit));
            check.put("phaseBudgetMessage", HourLineChecks.phaseBudgetMessage(line.phase(), budget));
            checks.add(check);
        }
        LOG.info("prepare-hours-submission job {}: {} line check(s) for {}", job.getKey(), checks.size(), workpackNumber);
        client.newCompleteCommand(job.getKey()).variables(Map.of("lineChecks", checks, "validationMessages", List.of())).send().join();
    }
}
