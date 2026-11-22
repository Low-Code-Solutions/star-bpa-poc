package com.lcs.star.bpa.task;

import com.lcs.star.bpa.domain.HourLineChecks;
import com.lcs.star.bpa.persistence.HoursSubmissionRepository;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * BPA-67 (S5; plan Q2): hours of a locked month cannot be approved. A Validated (Timekeeper) or Approved (Production Supervisor, PPC) decision on a submission with any line dated
 * in a month that BPA-64's month lock has locked is 409 "Hours for <Month YYYY> are locked"; Returned and Rejected stay allowed, so the hours do not get stuck (ASSUMPTION). The
 * submission is the task's {@code hoursSubmissionId}. Checked before the task is assigned or completed, so a refusal leaves it open (after {@link TaskValidity} and
 * {@link HoursSegregationCheck}).
 */
@Component
public class HoursMonthLockCheck {

    static final Set<String> ELEMENTS = Set.of("UserTask_ValidateHours", "UserTask_ApproveHoursProduction", "UserTask_ApproveHoursPpc");

    private final HoursSubmissionRepository submissions;

    public HoursMonthLockCheck(HoursSubmissionRepository submissions) {
        this.submissions = submissions;
    }

    boolean appliesTo(String elementId) {
        return ELEMENTS.contains(elementId);
    }

    /** The refusal (409 and the text) for a Validated or Approved decision on hours of a locked month; empty otherwise. */
    Optional<HoursSegregationCheck.Refusal> refusal(Map<String, Object> taskVariables, Map<String, Object> submitted) {
        Object decision = submitted.get("hoursDecision");
        if (!"Validated".equals(decision) && !"Approved".equals(decision)) {
            return Optional.empty();
        }
        if (!(taskVariables.get("hoursSubmissionId") instanceof String id) || id.isBlank()) {
            return Optional.empty(); // the worker fails the job on a missing id
        }
        Optional<LocalDate> locked = submissions.lockedMonthOf(UUID.fromString(id));
        return locked.map(date -> new HoursSegregationCheck.Refusal(HttpStatus.CONFLICT, HourLineChecks.monthLockedMessage(date)));
    }
}
