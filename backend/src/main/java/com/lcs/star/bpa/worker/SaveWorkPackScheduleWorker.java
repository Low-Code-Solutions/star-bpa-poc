package com.lcs.star.bpa.worker;

import com.lcs.star.bpa.domain.ScheduleValidator;
import com.lcs.star.bpa.persistence.WorkPackRepository;
import com.lcs.star.bpa.persistence.WorkPackScheduleRepository;
import com.lcs.star.bpa.task.TaskService;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Job type {@code save-workpack-schedule} (BPA-11) on {@code ServiceTask_SaveWorkPackSchedule}: validates the Production Planner's
 * planned dates and saves the Work Pack's schedule. Built with the new-job-worker skill.
 *
 * <p>Idempotent on the Work Pack (D27, D65, S7): a Work Pack that already has its schedule is left as it is and the job completes,
 * checked before anything else about the dates, so a retry after midnight cannot loop the task back. Invalid dates throw the BPMN
 * error {@code SCHEDULE_INVALID} with {@code validationMessages} (G2, D56), so the task returns showing them; the rules are
 * {@link ScheduleValidator}'s, with today from the injected clock in Asia/Riyadh (D28, 09 S9) and the lead time from
 * {@code app.lead-time-days} (D9, data model §6.2). Who: {@code completedBy} (D63), never the service user; a missing one, an
 * unknown Work Pack and a Work Pack that is not Released (the release gate, D25) are faults, not business rejections: the job
 * fails, retries, then ends as an incident (S7).
 */
@Component
public class SaveWorkPackScheduleWorker {

    static final String ERROR_CODE = "SCHEDULE_INVALID";
    static final String BAD_START = "Planned start is not a valid date"; // ASSUMPTION
    static final String BAD_END = "Planned end is not a valid date"; // ASSUMPTION

    private static final Logger LOG = LoggerFactory.getLogger(SaveWorkPackScheduleWorker.class);

    private final WorkPackRepository workPacks;
    private final WorkPackScheduleRepository schedules;
    private final Clock clock;
    private final ZoneId businessZone;
    private final int leadTimeDays;

    public SaveWorkPackScheduleWorker(WorkPackRepository workPacks, WorkPackScheduleRepository schedules, Clock clock,
            ZoneId businessZone, @Value("${app.lead-time-days}") int leadTimeDays) {
        this.workPacks = workPacks;
        this.schedules = schedules;
        this.clock = clock;
        this.businessZone = businessZone;
        this.leadTimeDays = leadTimeDays;
    }

    @JobWorker(type = "save-workpack-schedule", autoComplete = false)
    public void handle(JobClient client, ActivatedJob job) {
        Map<String, Object> variables = job.getVariablesAsMap();
        var workPack = workPacks.find(job.getProcessInstanceKey()).orElseThrow(() -> new IllegalStateException(
                "No Work Pack for process instance " + job.getProcessInstanceKey() + " (" + job.getElementId() + ")"));
        if (!(variables.get(TaskService.COMPLETED_BY) instanceof String username) || username.isBlank()) {
            throw new IllegalStateException("No completedBy on the schedule (" + job.getElementId() + ", job " + job.getKey() + ")");
        }
        if (schedules.exists(workPack.id())) {
            LOG.info("save-workpack-schedule job {} retried: {} is already scheduled", job.getKey(), workPack.workpackNumber());
            complete(client, job);
            return;
        }
        if (!"Released".equals(workPacks.status(workPack.id()).orElse(null))) {
            throw new IllegalStateException(workPack.workpackNumber() + " is not Released, so it cannot be scheduled (D25)");
        }

        // Every rule runs on what can be read (all messages are returned at once, D56): a date that is not a date adds its own
        // text and counts as read-nothing, so the validator does not also call it missing.
        List<String> messages = new ArrayList<>();
        LocalDate start = date(variables.get("plannedStartDate"), BAD_START, messages);
        LocalDate end = date(variables.get("plannedEndDate"), BAD_END, messages);
        boolean startUnreadable = messages.contains(BAD_START);
        boolean endUnreadable = messages.contains(BAD_END);
        LocalDate today = LocalDate.ofInstant(clock.instant(), businessZone);
        for (String message : ScheduleValidator.validate(start, end, today, leadTimeDays)) {
            boolean duplicate = (startUnreadable && ScheduleValidator.START_REQUIRED.equals(message))
                    || (endUnreadable && ScheduleValidator.END_REQUIRED.equals(message));
            if (!duplicate) {
                messages.add(message);
            }
        }
        if (!messages.isEmpty()) {
            LOG.info("save-workpack-schedule job {}: {} invalid: {}", job.getKey(), workPack.workpackNumber(), messages);
            client.newThrowErrorCommand(job.getKey()).errorCode(ERROR_CODE).errorMessage(String.join("; ", messages))
                    .variables(Map.of("validationMessages", messages)).send().join();
            return;
        }

        boolean saved = schedules.save(workPack.id(), start, end, username);
        LOG.info("{} {} {}..{} by {} (job {})", workPack.workpackNumber(), saved ? "scheduled" : "already scheduled", start, end,
                username, job.getKey());
        complete(client, job);
    }

    /** The output is the same on a re-run: it clears the messages of a task that was returned earlier (as BPA-23 does). */
    private static void complete(JobClient client, ActivatedJob job) {
        client.newCompleteCommand(job.getKey()).variables(Map.of("validationMessages", List.of())).send().join();
    }

    /** A date variable: empty means missing (left to the validator); text that is not a date adds the given message. */
    private static LocalDate date(Object value, String badMessage, List<String> messages) {
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeException e) {
            messages.add(badMessage);
            return null;
        }
    }
}
