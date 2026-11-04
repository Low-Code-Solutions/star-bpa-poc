package com.lcs.star.bpa.task;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The per-task variable allow-lists held in backend configuration (09 S14, ADR 0004), keyed by the user task's BPMN element ID.
 * {@code write}: the only variable names a completion may carry (400 otherwise). {@code read}: the variables
 * {@code GET /api/v1/tasks/{key}} returns. A task whose element ID is not configured is not served by the task API.
 *
 * @param processId the process whose user tasks are served (Process_PPC_WorkPackAssignment)
 * @param allowLists element ID to its read and write lists
 */
@ConfigurationProperties("bpa.tasks")
public record TaskAllowList(String processId, Map<String, Lists> allowLists) {

    public TaskAllowList {
        allowLists = allowLists == null ? Map.of() : Map.copyOf(allowLists);
    }

    public Optional<Lists> forElement(String elementId) {
        return Optional.ofNullable(allowLists.get(elementId));
    }

    public record Lists(List<String> read, List<String> write) {

        public Lists {
            read = read == null ? List.of() : List.copyOf(read);
            write = write == null ? List.of() : List.copyOf(write);
        }

        Set<String> writable() {
            return Set.copyOf(write);
        }
    }
}
