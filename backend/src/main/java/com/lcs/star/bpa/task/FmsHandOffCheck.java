package com.lcs.star.bpa.task;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * BPA-58: the Send hours to FMS completion must name every file of the task, and only those (the task's own {@code fmsHoursExports}), so a file of
 * another week cannot be given an outcome from this task. Texts are ASSUMPTIONs (plan Q11): "Outcome is required for each file" when a file has no entry,
 * "File is not part of this task" for an entry naming another. {@link TaskValidity} checks each entry's outcome and reason first. Pure given the two maps.
 */
@Component
public class FmsHandOffCheck {

    static final String ELEMENT = "UserTask_SendHoursToFms";
    static final String NOT_PART = "File is not part of this task";

    boolean appliesTo(String elementId) {
        return ELEMENT.equals(elementId);
    }

    /** The message to answer with (400), or empty when the completion names exactly the task's files. */
    Optional<String> refusal(Map<String, Object> taskVariables, Map<String, Object> submitted) {
        Set<String> expected = ids(taskVariables.get("fmsHoursExports"), "id");
        Set<String> named = ids(submitted.get("fmsHandOffs"), "fmsHoursExportId");
        if (!named.containsAll(expected)) {
            return Optional.of(TaskValidity.OUTCOME_REQUIRED);
        }
        if (!expected.containsAll(named)) {
            return Optional.of(NOT_PART);
        }
        return Optional.empty();
    }

    private static Set<String> ids(Object list, String key) {
        Set<String> ids = new HashSet<>();
        if (list instanceof List<?> entries) {
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> map && map.get(key) instanceof String id) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }
}
