package com.lcs.star.bpa.worker;

import com.lcs.star.bpa.timesheet.HoursReminderService;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Job type {@code send-hours-reminder} on {@code ServiceTask_SendHoursReminderTimekeeper}, {@code ...ProductionSupervisor} and {@code ...Ppc} (BPA-74; E2-24; 09 S7,
 * ADR 0007), reached by the non-interrupting {@code R/PT24H} timer on each approval task. The headers say which step and which group: {@code step} (Timekeeper,
 * ProductionSupervisor, Ppc) and {@code recipientGroup}. The rule (48 hours since the hours reached the step, then every day) and the sending are in
 * {@link HoursReminderService}; this worker reads the job and completes it. Idempotent on the timer firing, which is the element instance key (a retry of the
 * job keeps it, the next day's firing has a new one). With no header or no {@code workpackNumber} the job fails with no retries; a mail failure is not caught:
 * Camunda's defaults apply, three tries and then an incident (S7).
 */
@Component
public class SendHoursReminderWorker {

    static final List<String> STEPS = List.of("Timekeeper", "ProductionSupervisor", "Ppc");

    private static final Logger LOG = LoggerFactory.getLogger(SendHoursReminderWorker.class);

    private final HoursReminderService reminders;

    public SendHoursReminderWorker(HoursReminderService reminders) {
        this.reminders = reminders;
    }

    @JobWorker(type = "send-hours-reminder", autoComplete = false)
    public void handle(JobClient client, ActivatedJob job) {
        Map<String, String> headers = job.getCustomHeaders();
        String step = headers.get("step");
        String group = headers.get("recipientGroup");
        Object workpack = job.getVariablesAsMap().get("workpackNumber");
        if (step == null || !STEPS.contains(step) || group == null || group.isBlank() || !(workpack instanceof String workpackNumber) || workpackNumber.isBlank()) {
            LOG.warn("send-hours-reminder job {} (instance {}): the step, the recipient group or the workpackNumber is missing or wrong", job.getKey(), job.getProcessInstanceKey());
            client.newFailCommand(job.getKey()).retries(0).errorMessage("the step, the recipient group or the workpackNumber is missing or wrong").send().join();
            return;
        }
        int reminded = reminders.remind(step, group, job.getProcessInstanceKey(), job.getElementInstanceKey(), workpackNumber);
        LOG.info("send-hours-reminder job {}: step {}, group {}: {} user(s) reminded for {}", job.getKey(), step, group, reminded, workpackNumber);
        client.newCompleteCommand(job.getKey()).send().join();
    }
}
