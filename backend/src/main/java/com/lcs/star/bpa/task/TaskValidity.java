package com.lcs.star.bpa.task;

import java.util.Map;
import java.util.Optional;

/**
 * BPA-9 (test-first stub, replaced when the check is built): accepts every completion, which is the expected red state for the
 * BPA9_ tests that need the backend re-check (09 S14).
 */
public final class TaskValidity {

    private TaskValidity() {
    }

    public static Optional<String> check(String elementId, Map<String, Object> submitted) {
        return Optional.empty();
    }
}
