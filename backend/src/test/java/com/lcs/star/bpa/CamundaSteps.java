package com.lcs.star.bpa;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcs.star.bpa.CamundaRest.Reply;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.StreamSupport;

/**
 * Test helper (BPA-23): Camunda reads and stand-in moves as the demo admin, and polling for Camunda's eventually consistent
 * search (BPA-25). The same steps as in HourJournalHeaderApiTest (BPA-7), shared from here for new test classes.
 */
final class CamundaSteps {

    static final String PROCESS = "Process_PPC_WorkPackAssignment";
    private static final ObjectMapper JSON = new ObjectMapper();

    private CamundaSteps() {
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

    /** Test stand-in: terminate whatever is active and activate one element, retrying while the search lags (BPA-7). */
    static void moveTokenTo(long instance, String toElementId) {
        long until = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        Reply last = null;
        while (System.nanoTime() < until) {
            List<JsonNode> active = activeElements(instance);
            if (!active.isEmpty()) {
                last = IntegrationApp.admin.post("/v2/process-instances/" + instance + "/modification", Map.of(
                        "activateInstructions", List.of(Map.of("elementId", toElementId)),
                        "terminateInstructions", active.stream().map(e -> Map.of("elementInstanceKey", e.path("elementInstanceKey").asText())).toList()));
                if (last.status() == 204) {
                    return;
                }
            }
            pause();
        }
        throw new AssertionError("could not move " + instance + " to " + toElementId + "; last: " + (last == null ? "nothing active" : last.body()));
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
