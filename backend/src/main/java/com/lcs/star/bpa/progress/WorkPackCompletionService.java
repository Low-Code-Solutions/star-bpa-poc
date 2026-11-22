package com.lcs.star.bpa.progress;

import com.lcs.star.bpa.adapter.CamundaProcessClient;
import com.lcs.star.bpa.persistence.AssignmentFileRepository;
import com.lcs.star.bpa.persistence.ProgressPeriodRepository;
import com.lcs.star.bpa.persistence.ProgressPeriodRepository.Period;
import com.lcs.star.bpa.persistence.ProgressWeekRepository;
import com.lcs.star.bpa.persistence.ProgressWeekRepository.AtHundred;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Completes the Work Packs the Project Manager's approved week finishes (BPA-67; E2-16; FRD-7 status list; the POC plays FMS's role for Work Packs, C2 and D47), run by
 * {@code ServiceTask_CompleteWorkPacks} after {@code Gateway_IsWeekApproved}, just before the lock:
 * <ol>
 * <li>{@link #complete}: in one transaction, with the period row held ({@code requireOpen}), every Work Pack whose approved % for the week is <b>exactly</b> 100 becomes
 * {@code Completed} (99.99 stays In Progress), one {@code audit_log} row each; a Work Pack that is Completed already is not written again (the re-run key is the values).</li>
 * <li>{@link #publish}: then, for each of those Work Packs, the message {@code WorkPackCompleted} (correlation key and message id the Work Pack number) ends its
 * Process_PPC_TimesheetToProgress instance through BoundaryEvent_WorkPackCompleted. The time to live is P7D (ASSUMPTION, Jira): while the hours are in approval the message waits,
 * buffered, until the instance is back at UserTask_BookHours (ASSUMPTION, Jira). The duplicate guard is {@code published_message} as BPA-49 (publish, then record; a crash in
 * between publishes again and the message id stops a duplicate while it is buffered, ADR 0007). A publish failure is not caught: Camunda retries the job, and the Work Packs are
 * already committed.</li>
 * </ol>
 */
@Service
public class WorkPackCompletionService {

    public static final String MESSAGE = "WorkPackCompleted";

    /** P7D in milliseconds (ASSUMPTION, plan Q1). */
    public static final long TIME_TO_LIVE_MILLIS = 7L * 24 * 60 * 60 * 1000;

    private static final Logger LOG = LoggerFactory.getLogger(WorkPackCompletionService.class);

    private final ProgressPeriodRepository periods;
    private final ProgressWeekRepository weeks;
    private final AssignmentFileRepository files;
    private final CamundaProcessClient camunda;
    private final Clock clock;

    public WorkPackCompletionService(ProgressPeriodRepository periods, ProgressWeekRepository weeks, AssignmentFileRepository files, CamundaProcessClient camunda, Clock clock) {
        this.periods = periods;
        this.weeks = weeks;
        this.files = files;
        this.camunda = camunda;
        this.clock = clock;
    }

    /** Completes the Work Packs approved at 100% for the week and returns the numbers of all of them (also those completed by an earlier run), in order. A Locked week is refused. */
    @Transactional
    public List<String> complete(Period period, String username) {
        periods.requireOpen(period);
        List<AtHundred> atHundred = weeks.approvedAtHundred(period.id());
        for (AtHundred workpack : atHundred) {
            if (weeks.markCompleted(workpack, username, clock.instant())) {
                LOG.info("{} completed for the week ending {}", workpack.workpackNumber(), period.weekEnding());
            }
        }
        return atHundred.stream().map(AtHundred::workpackNumber).toList();
    }

    /** Publishes {@code WorkPackCompleted} for each Work Pack that has no record of it yet. */
    public void publish(List<String> workpackNumbers, String username) {
        for (String number : workpackNumbers) {
            if (files.published(MESSAGE, number)) {
                continue;
            }
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put("workpackNumber", number);
            camunda.publish(MESSAGE, number, number, variables, TIME_TO_LIVE_MILLIS);
            // False: a concurrent run recorded it first; both published and the message id stopped the duplicate while buffered
            files.claim(MESSAGE, number, username, clock.instant());
            LOG.info("{} published for {}", MESSAGE, number);
        }
    }
}
