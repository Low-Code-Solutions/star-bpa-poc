package com.lcs.star.bpa;

import static io.camunda.process.test.api.CamundaAssert.assertThat;
import static io.camunda.process.test.api.assertions.ProcessInstanceSelectors.byKey;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ProcessInstanceEvent;
import io.camunda.process.test.api.CamundaProcessTestContext;
import com.lcs.star.bpatest.ProcessTestApplication;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * BPA-43 Part A: the skeleton worker on the real model. It proves deploy, activation and completion only; it implements
 * no story's acceptance criteria. The save-workpack job belongs to BPA-6, so the test completes it itself.
 */
@SpringBootTest(classes = ProcessTestApplication.class)
@CamundaSpringProcessTest
@ActiveProfiles("test")
class SkeletonWorkerProcessTest {

    @Autowired
    CamundaClient client;

    @Autowired
    CamundaProcessTestContext processTestContext;

    @Test
    void BPA43_createHourJournalHeaderJobIsHandledByTheWorker() {
        ProcessInstanceEvent instance = client.newCreateInstanceCommand()
                .bpmnProcessId("Process_PPC_WorkPackAssignment").latestVersion().send().join();

        processTestContext.completeUserTask("UserTask_CreateWorkPack");
        processTestContext.completeJob("save-workpack");
        processTestContext.completeJob("create-hour-journal-header"); // BPA-7 replaced the skeleton stub; its worker needs a database

        assertThat(byKey(instance.getProcessInstanceKey()))
                .hasCompletedElements("ServiceTask_SaveWorkPack", "ServiceTask_CreateHourJournalHeader")
                .hasActiveElements("UserTask_ConfirmWorkPack");
    }
}
