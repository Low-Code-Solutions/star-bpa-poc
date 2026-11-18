package com.lcs.star.bpa.task;

import com.lcs.star.bpa.persistence.HoursSubmissionRepository;
import com.lcs.star.bpa.worker.RecordHoursDecisionWorker;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Segregation of duties for the hours approvals (BPA-56; data model §13.2.2; FRD-21 §21.8.1 spirit, ASSUMPTION for hours): the user who decides at the
 * Production Supervisor step must not be the user who sent the hours. The worker checks it too (the data model's "worker-checked"); checking here first
 * keeps the task open with a 403 instead of completing it into an incident (plan Q1, ASSUMPTION status and text). With one test user per group it always
 * holds; it is still coded. UserTask_ApproveHoursPpc joins with BPA-57.
 */
@Component
public class HoursSegregationCheck {

    static final Set<String> ELEMENTS = Set.of("UserTask_ApproveHoursProduction");

    private final HoursSubmissionRepository submissions;

    public HoursSegregationCheck(HoursSubmissionRepository submissions) {
        this.submissions = submissions;
    }

    /** True when the check applies to this task; only then are the task's variables read. */
    boolean appliesTo(String elementId) {
        return ELEMENTS.contains(elementId);
    }

    /** The refusal text when {@code username} sent the submission named by the task's {@code hoursSubmissionId}; empty otherwise. */
    Optional<String> refusal(Map<String, Object> taskVariables, String username) {
        if (!(taskVariables.get("hoursSubmissionId") instanceof String id) || id.isBlank()) {
            return Optional.empty(); // the worker fails the job on a missing id
        }
        return submissions.submittedBy(UUID.fromString(id)).filter(username::equals).map(RecordHoursDecisionWorker::segregationMessage);
    }
}
