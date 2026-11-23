package com.lcs.star.bpa;

import static io.camunda.process.test.api.CamundaAssert.assertThat;
import static io.camunda.process.test.api.assertions.ProcessInstanceSelectors.byKey;

import com.lcs.star.bpatest.ProcessTestApplication;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ProcessInstanceEvent;
import io.camunda.process.test.api.CamundaProcessTestContext;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * BPA-74 (E2-24): the timers of the model, with the engine's time moved forward (Camunda Process Test time travel; nothing waits for real). The model, not the
 * worker, owns the "every 24 hours while the approval task waits" part: a non-interrupting {@code R/PT24H} boundary event on each approval task starts
 * {@code send-hours-reminder} (here mocked to complete), and a decision, which completes the task, stops it. The 48 hour rule, the recipients and the
 * messages belong to the worker and are proved in {@link HoursReminderApiTest}. These tests cover the existing timers (BPA-48), so they are expected to
 * pass at their first run.
 */
@SpringBootTest(classes = ProcessTestApplication.class)
@CamundaSpringProcessTest
@ActiveProfiles("test")
class HoursReminderProcessTest {

    static final String PROCESS = "Process_PPC_TimesheetToProgress";
    static final String REMINDER = "ServiceTask_SendHoursReminderTimekeeper";

    @Autowired
    CamundaClient client;

    @Autowired
    CamundaProcessTestContext context;

    @Test
    void timerFiresEveryDayWhileTheHoursWait() {
        context.mockJobWorker("send-hours-reminder").thenComplete();
        ProcessInstanceEvent instance = atValidateHours();

        context.increaseTime(Duration.ofHours(23).plusMinutes(59));
        assertThat(byKey(instance.getProcessInstanceKey())).hasActiveElements("UserTask_ValidateHours").hasNotActivatedElements(REMINDER);

        context.increaseTime(Duration.ofMinutes(1)); // 24 h
        assertThat(byKey(instance.getProcessInstanceKey())).hasCompletedElement(REMINDER, 1).hasActiveElements("UserTask_ValidateHours");

        context.increaseTime(Duration.ofHours(24)); // 48 h: the next firing
        assertThat(byKey(instance.getProcessInstanceKey())).hasCompletedElement(REMINDER, 2);

        context.increaseTime(Duration.ofHours(24)); // 72 h
        assertThat(byKey(instance.getProcessInstanceKey())).hasCompletedElement(REMINDER, 3).hasActiveElements("UserTask_ValidateHours");
    }

    @Test
    void noTimerOnceTheTaskIsDecided() {
        context.mockJobWorker("send-hours-reminder").thenComplete();
        ProcessInstanceEvent instance = atValidateHours();
        context.increaseTime(Duration.ofHours(24));
        assertThat(byKey(instance.getProcessInstanceKey())).hasCompletedElement(REMINDER, 1);

        context.completeUserTask("UserTask_ValidateHours", Map.of("hoursDecision", "Validated"));
        context.increaseTime(Duration.ofHours(72));

        assertThat(byKey(instance.getProcessInstanceKey())).hasCompletedElement(REMINDER, 1); // no firing after the decision
    }

    private ProcessInstanceEvent atValidateHours() {
        return client.newCreateInstanceCommand().bpmnProcessId(PROCESS).latestVersion()
                .variables(Map.of("workpackNumber", "WP-000457")).startBeforeElement("UserTask_ValidateHours").send().join();
    }
}
