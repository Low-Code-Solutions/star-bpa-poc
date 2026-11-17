package com.lcs.star.bpa;

import static org.assertj.core.api.Assertions.assertThat;

import com.lcs.star.bpa.CamundaRest.Reply;
import com.lcs.star.bpa.adapter.CamundaAdminClient;
import com.lcs.star.bpa.adapter.CamundaIdentityClient;
import com.lcs.star.bpa.config.PersonaFile;
import com.lcs.star.bpa.config.PersonaSync;
import org.springframework.core.io.ClassPathResource;

/**
 * Test helper: the background every app-against-Camunda test needs (BPA-26, BPA-6). The integration Camunda, the S22 persona
 * sync, the deployed models, the demo admin and the {@code bpa-service} password that works. Once per JVM; the calls are
 * idempotent.
 */
final class IntegrationApp {

    static CamundaRest admin;
    static String servicePassword;

    private IntegrationApp() {
    }

    static synchronized void background() {
        if (admin != null) {
            return;
        }
        IntegrationCamunda.start();
        String url = IntegrationCamunda.restUrl();
        var adminClient = new CamundaAdminClient(url, IntegrationCamunda.ADMIN, IntegrationCamunda.ADMIN_PASSWORD);
        new PersonaSync(adminClient, IntegrationCamunda.jdbc(), PersonaFile.load(new ClassPathResource("personas.yaml")),
                PersonaSyncTest.DEMO_PASSWORD, PersonaSyncTest.SERVICE_PASSWORD).sync();
        // PersonaSyncTest may have made bpa-service first with another password; the sync keeps it (as in Bpa25CamundaChecksTest).
        servicePassword = new CamundaIdentityClient(url).verify("bpa-service", PersonaSyncTest.SERVICE_PASSWORD).isPresent()
                ? PersonaSyncTest.SERVICE_PASSWORD : PersonaSyncTest.EXISTING_SERVICE_PASSWORD;
        admin = new CamundaRest(url, IntegrationCamunda.ADMIN, IntegrationCamunda.ADMIN_PASSWORD);
        Reply deployed = admin.deploy(
                new ClassPathResource("bpmn/Process_PPC_WorkPackAssignment.bpmn"),
                new ClassPathResource("bpmn/Process_PPC_WorkerReassignment.bpmn"),
                new ClassPathResource("bpmn/Process_PPC_DrawingRevisionNotice.bpmn"),
                new ClassPathResource("bpmn/Process_PPC_TimesheetToProgress.bpmn"), // BPA-49: the process the Epic 1 end event starts
                new ClassPathResource("dmn/Decision_WorkpackReleaseCheck.dmn"),
                new ClassPathResource("dmn/Decision_HoursWithinBudget.dmn")); // used by its BusinessRuleTask
        assertThat(deployed.status()).as("models deploy: " + deployed.body()).isEqualTo(200);
    }
}
