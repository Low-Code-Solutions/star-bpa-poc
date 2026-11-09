package com.lcs.star.bpa;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.lcs.star.bpa.CamundaRest.Reply;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * BPA-25 check 7, recorded for BPA-13 (data model section 8: "the code is built against the observed response"): what Camunda 8.10.1 answers to
 * {@code POST /v2/messages/correlation} for the message-start event {@code AssignmentFileReplaced} (Process_PPC_WorkerReassignment, correlation
 * key = workpackNumber, TTL 0), as {@code bpa-service}'s correlate call does. Camunda only, no application workers: the instance stays
 * active at ServiceTask_ReplaceAssignment until this test completes its job. The upload endpoint's reverts depend on these answers:
 * "Replacement already in progress" when no instance starts (C3), "Replacement could not be started; try again" on an error (D75).
 * Not a Gherkin scenario.
 */
@Tag("integration")
class Bpa13CorrelationChecksTest {

    static final String PROCESS = "Process_PPC_WorkerReassignment";

    @BeforeAll
    static void start() {
        IntegrationApp.background();
    }

    private static Reply correlate(String key) {
        return IntegrationApp.admin.post("/v2/messages/correlation",
                Map.of("name", "AssignmentFileReplaced", "correlationKey", key, "variables", Map.of("workpackNumber", key, "assignmentFileId", UUID.randomUUID().toString())));
    }

    /** The distinct reassignment instances started for this key (found by the variable the message carries). */
    private static Set<String> instancesFor(String key) {
        Set<String> found = new HashSet<>();
        Reply variables = IntegrationApp.admin.post("/v2/variables/search", Map.of("filter", Map.of("name", "workpackNumber"), "page", Map.of("limit", 1000)));
        for (JsonNode variable : variables.body().path("items")) {
            if (variable.path("value").asText().contains(key)) {
                found.add(variable.path("processInstanceKey").asText());
            }
        }
        return found;
    }

    /** Completes the replace-worker-assignment job of this instance, as the real worker would (replacementApplied true). */
    private static void completeReplaceJob(String instance) {
        for (int attempt = 0; attempt < 30; attempt++) {
            Reply activated = IntegrationApp.admin.post("/v2/jobs/activation",
                    Map.of("type", "replace-worker-assignment", "worker", "bpa13-probe", "timeout", 60000, "maxJobsToActivate", 20, "requestTimeout", 2000));
            for (JsonNode job : activated.body().path("jobs")) {
                if (instance.equals(job.path("processInstanceKey").asText())) {
                    Reply done = IntegrationApp.admin.post("/v2/jobs/" + job.path("jobKey").asText() + "/completion", Map.of("variables", Map.of("replacementApplied", true)));
                    assertThat(done.status()).as("job completion: " + done.body()).isEqualTo(204);
                    return;
                }
            }
        }
        throw new AssertionError("no replace-worker-assignment job activated for instance " + instance);
    }

    @Test
    void BPA13_probeCorrelateOfTheReplacementMessage() {
        String key = "BPA13-WP-" + UUID.randomUUID();

        // 1. nothing is running for the key: correlate starts the message-start instance (BPA-25 check 7a)
        Reply first = correlate(key);
        assertThat(first.status()).as("first correlate: " + first.body()).isEqualTo(200);
        String instance = first.body().path("processInstanceKey").asText();
        assertThat(instance).isNotBlank();

        // 2. the instance is still active: a second correlate for the same key starts nothing (check 7b); record the exact answer
        Reply second = correlate(key);
        System.out.println("BPA-13 PROBE second correlate while active: status=" + second.status() + " body=" + second.body());
        assertThat(second.status()).as("second correlate while active: " + second.body()).isEqualTo(404);
        // the search is eventually consistent: wait for the first instance to show, give a second one time to appear, then count
        CamundaSteps.poll(() -> instancesFor(key), found -> found.contains(instance), "the instance of " + key + " in the variable search");
        for (int i = 0; i < 4; i++) {
            CamundaSteps.pause();
        }
        assertThat(instancesFor(key)).as("still one instance for the key").containsExactly(instance);

        // control: another key starts its own instance (the guard is per key)
        Reply other = correlate("BPA13-WP-" + UUID.randomUUID());
        assertThat(other.status()).as("a different key: " + other.body()).isEqualTo(200);
        assertThat(other.body().path("processInstanceKey").asText()).isNotEqualTo(instance);

        // 3. the instance ends: the key is free again (check 7d)
        completeReplaceJob(instance);
        IntegrationApp.admin.pollGet("/v2/process-instances/" + instance, r -> "COMPLETED".equals(r.body().path("state").asText()));
        Reply again = correlate(key);
        System.out.println("BPA-13 PROBE correlate after the instance ended: status=" + again.status() + " body=" + again.body());
        assertThat(again.status()).as("correlate after the instance ended: " + again.body()).isEqualTo(200);
        assertThat(again.body().path("processInstanceKey").asText()).isNotEqualTo(instance);
    }
}
