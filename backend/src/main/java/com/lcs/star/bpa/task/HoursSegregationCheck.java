package com.lcs.star.bpa.task;

import com.lcs.star.bpa.persistence.HoursSubmissionRepository;
import com.lcs.star.bpa.worker.RecordHoursDecisionWorker;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Segregation of duties for the hours approvals (BPA-56, BPA-57; data model §13.2.2; FRD-21 §21.8.1 spirit, ASSUMPTION for hours). The worker checks the
 * same rules (the data model's "worker-checked"); checking here first keeps the task open with an answer instead of completing it into an incident.
 * <ul>
 * <li>UserTask_ApproveHoursProduction (BPA-56): the user who decides must not be the user who sent the hours: <b>403</b>, with the text of
 * {@link RecordHoursDecisionWorker#segregationMessage(String)} (ASSUMPTION status and text, plan Q1).</li>
 * <li>UserTask_ApproveHoursPpc (BPA-57): the same sender check first (403, BPA-56's text), then the user must not have decided the same submission at an
 * earlier step, the Timekeeper included (plan Q2): <b>409</b> "You already decided these hours at an earlier step" (the story's scenario text).</li>
 * </ul>
 * With one test user per group the sender case always holds; the checks are still coded and tested by seeding {@code submitted_by} and {@code decided_by}.
 */
@Component
public class HoursSegregationCheck {

    /** Why the user may not decide: the HTTP status and the text to answer with. */
    record Refusal(HttpStatus status, String message) {
    }

    static final String PRODUCTION = "UserTask_ApproveHoursProduction";
    static final String PPC = "UserTask_ApproveHoursPpc";
    static final Set<String> ELEMENTS = Set.of(PRODUCTION, PPC);

    private final HoursSubmissionRepository submissions;

    public HoursSegregationCheck(HoursSubmissionRepository submissions) {
        this.submissions = submissions;
    }

    /** True when the check applies to this task; only then are the task's variables read. */
    boolean appliesTo(String elementId) {
        return ELEMENTS.contains(elementId);
    }

    /** The refusal when {@code username} may not decide the submission named by the task's {@code hoursSubmissionId}; empty otherwise. */
    Optional<Refusal> refusal(String elementId, Map<String, Object> taskVariables, String username) {
        if (!(taskVariables.get("hoursSubmissionId") instanceof String id) || id.isBlank()) {
            return Optional.empty(); // the worker fails the job on a missing id
        }
        UUID submissionId = UUID.fromString(id);
        if (submissions.submittedBy(submissionId).filter(username::equals).isPresent()) {
            return Optional.of(new Refusal(HttpStatus.FORBIDDEN, RecordHoursDecisionWorker.segregationMessage(username)));
        }
        if (PPC.equals(elementId) && submissions.earlierDeciders(submissionId, "Ppc").contains(username)) {
            return Optional.of(new Refusal(HttpStatus.CONFLICT, RecordHoursDecisionWorker.EARLIER_DECIDER));
        }
        return Optional.empty();
    }
}
