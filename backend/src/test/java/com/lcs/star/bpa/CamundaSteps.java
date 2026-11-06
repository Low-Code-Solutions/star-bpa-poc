package com.lcs.star.bpa;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcs.star.bpa.CamundaRest.Reply;
import com.lcs.star.bpa.api.TestBrowser;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.StreamSupport;

/**
 * Test helper (BPA-23): Camunda reads and stand-in moves as the demo admin, and polling for Camunda's eventually consistent
 * search (BPA-25). The steps BPA-7 first wrote inside HourJournalHeaderApiTest, shared from here for new test classes; the
 * older classes keep their own copies until a clean-up moves them here.
 */
final class CamundaSteps {

    static final String PROCESS = "Process_PPC_WorkPackAssignment";
    private static final ObjectMapper JSON = new ObjectMapper();

    private CamundaSteps() {
    }

    /**
     * Cancels every instance still waiting at {@code elementId}, whichever test class left it (all classes share one Camunda, each with its own
     * database, so Work Pack numbers repeat and a message correlated by Work Pack number could reach an old instance; BPA-12 CI run).
     */
    static void cancelInstancesWaitingAt(String elementId) {
        for (JsonNode element : elements(IntegrationApp.admin.post("/v2/element-instances/search",
                Map.of("filter", Map.of("elementId", elementId, "state", "ACTIVE"), "page", Map.of("limit", 1000))).body().path("items"))) {
            IntegrationApp.admin.post("/v2/process-instances/" + element.path("processInstanceKey").asText() + "/cancellation", Map.of());
        }
    }

    /** Open (CREATED) user tasks of one element in one instance. */
    static List<JsonNode> openTasks(long instance, String elementId) {
        Reply found = IntegrationApp.admin.post("/v2/user-tasks/search",
                Map.of("filter", Map.of("processInstanceKey", Long.toString(instance), "elementId", elementId, "state", "CREATED")));
        return elements(found.body().path("items"));
    }

    static JsonNode awaitOpenTask(long instance, String elementId) {
        return poll(() -> openTasks(instance, elementId), list -> !list.isEmpty(), elementId + " of " + instance).get(0);
    }

    /** True once the element has completed at least this many times in the instance. */
    static boolean completed(long instance, String elementId, int times) {
        return elements(IntegrationApp.admin.post("/v2/element-instances/search", Map.of(
                "filter", Map.of("processInstanceKey", Long.toString(instance), "elementId", elementId, "state", "COMPLETED"),
                "page", Map.of("limit", 50))).body().path("items")).size() >= times;
    }

    /** The instance's active flow elements (not the process itself). */
    static List<JsonNode> activeElements(long instance) {
        return elements(IntegrationApp.admin.post("/v2/element-instances/search",
                        Map.of("filter", Map.of("processInstanceKey", Long.toString(instance), "state", "ACTIVE"), "page", Map.of("limit", 50)))
                .body().path("items")).stream()
                .filter(e -> !"PROCESS".equals(e.path("type").asText()) && !PROCESS.equals(e.path("elementId").asText()))
                .toList();
    }

    /** The open Sign off release task of the instance, as the Project Engineer's task key (BPA-9). */
    static String awaitSignOffTask(long instance) {
        return awaitOpenTask(instance, "UserTask_SignOffRelease").path("userTaskKey").asText();
    }

    /**
     * The Project Engineer completes Sign off release through the task API (BPA-9); the reason is sent only when given.
     * Returns the response, so a test can also check a refusal.
     */
    static HttpResponse<String> completeSignOff(TestBrowser projectEngineer, String taskKey,
            String decision, String reason) {
        Map<String, Object> variables = new LinkedHashMap<>();
        if (decision != null) {
            variables.put("signOffDecision", decision);
        }
        if (reason != null) {
            variables.put("declineReason", reason);
        }
        return projectEngineer.post("/api/v1/tasks/" + taskKey + "/complete", toJson(Map.of("variables", variables)));
    }

    /** A process variable's value as JSON text, as Camunda holds it (null when absent). */
    static String variable(long instance, String name) {
        List<JsonNode> found = elements(IntegrationApp.admin.post("/v2/variables/search",
                Map.of("filter", Map.of("processInstanceKey", Long.toString(instance), "name", name))).body().path("items"));
        return found.isEmpty() ? null : found.get(0).path("value").asText();
    }

    static <T> void assertStaysEmpty(Supplier<List<T>> read, String what) {
        long until = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < until) {
            assertThat(read.get()).as("no " + what).isEmpty();
            pause();
        }
    }

    static <T> T poll(Supplier<T> read, Predicate<T> done, String what) {
        long until = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        T last = read.get();
        while (!done.test(last)) {
            if (System.nanoTime() > until) {
                throw new AssertionError("timed out waiting for " + what + "; last: " + last);
            }
            pause();
            last = read.get();
        }
        return last;
    }

    static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static JsonNode json(String body) {
        try {
            return JSON.readTree(body);
        } catch (java.io.IOException e) {
            throw new AssertionError("not JSON: " + body, e);
        }
    }

    static List<JsonNode> elements(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).toList();
    }

    static void pause() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
