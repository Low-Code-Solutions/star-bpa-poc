package com.lcs.star.bpa.timesheet;

import com.lcs.star.bpa.adapter.CamundaProcessClient;
import com.lcs.star.bpa.persistence.AssignmentFileRepository;
import com.lcs.star.bpa.persistence.WorkPackRepository;
import com.lcs.star.bpa.persistence.WorkPackRepository.SavedWorkPack;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * BPA-49 (E2-D18): starts a Work Pack's timesheet process by publishing WorkPackAssigned, once. Used by the job worker behind
 * EndEvent_WorkPackAssigned and by the one-off endpoint. The duplicate guard is the database ({@code published_message}, sha256 = the message
 * name; ADR 0007: with TTL 0 the message id does not stop a second publish). Order: publish, then record. A crash in between leaves no row, so
 * the retry publishes again and Camunda drops it while an instance with the same correlation key is active; recording first would turn that
 * crash into a Work Pack that never gets a process.
 */
@Service
public class TimesheetProcessStarter {

    public enum Outcome { STARTED, ALREADY_RUNNING, WORK_PACK_NOT_FOUND, NOT_ASSIGNED }

    public static final String MESSAGE = "WorkPackAssigned";

    private static final Logger LOG = LoggerFactory.getLogger(TimesheetProcessStarter.class);

    private final WorkPackRepository workPacks;
    private final AssignmentFileRepository files;
    private final CamundaProcessClient camunda;
    private final Clock clock;

    public TimesheetProcessStarter(WorkPackRepository workPacks, AssignmentFileRepository files, CamundaProcessClient camunda, Clock clock) {
        this.workPacks = workPacks;
        this.files = files;
        this.camunda = camunda;
        this.clock = clock;
    }

    /** The one-off start: only for an Assigned Work Pack that has no record of a start. */
    public Outcome start(String workpackNumber, String username) {
        Optional<SavedWorkPack> workPack = workPacks.findByNumber(workpackNumber);
        if (workPack.isEmpty()) {
            return Outcome.WORK_PACK_NOT_FOUND;
        }
        if (files.published(MESSAGE, workpackNumber)) {
            return Outcome.ALREADY_RUNNING;
        }
        boolean assigned = files.workPack(workpackNumber).map(s -> "Assigned".equals(s.assignmentState())).orElse(false);
        if (!assigned) {
            return Outcome.NOT_ASSIGNED;
        }
        return publish(workPack.get(), username);
    }

    /** The end event's start: the Work Pack is known to be assigned (the job runs right after the time sheets are created), so only the record is checked. */
    public Outcome publish(SavedWorkPack workPack, String username) {
        if (files.published(MESSAGE, workPack.workpackNumber())) {
            return Outcome.ALREADY_RUNNING;
        }
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("workpackId", workPack.id().toString());
        variables.put("workpackNumber", workPack.workpackNumber());
        camunda.publish(MESSAGE, workPack.workpackNumber(), workPack.workpackNumber(), variables);
        // False: a concurrent call recorded the same Work Pack first. Both published; Camunda dropped one, so there is one process, and this caller is the second.
        if (!files.claim(MESSAGE, workPack.workpackNumber(), username, clock.instant())) {
            return Outcome.ALREADY_RUNNING;
        }
        LOG.info("{} published for {}", MESSAGE, workPack.workpackNumber());
        return Outcome.STARTED;
    }
}
