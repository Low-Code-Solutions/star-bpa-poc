package com.lcs.star.bpa.assignment;

import com.lcs.star.bpa.domain.AssignmentRowValidator;
import com.lcs.star.bpa.domain.AssignmentRowValidator.Row;
import com.lcs.star.bpa.domain.AssignmentRowValidator.Verdict;
import com.lcs.star.bpa.domain.ReplacementPlanner;
import com.lcs.star.bpa.domain.ReplacementPlanner.Current;
import com.lcs.star.bpa.domain.ReplacementPlanner.Step;
import com.lcs.star.bpa.domain.ReplacementPlanner.Wanted;
import com.lcs.star.bpa.persistence.DigitalTimesheetRepository;
import com.lcs.star.bpa.persistence.HourJournalLineRepository;
import com.lcs.star.bpa.persistence.WorkerAssignmentRepository;
import com.lcs.star.bpa.persistence.WorkerAssignmentRepository.FileRow;
import com.lcs.star.bpa.persistence.WorkerReplacementRepository;
import com.lcs.star.bpa.persistence.WorkerReplacementRepository.Assigned;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The work of {@code replace-worker-assignment} (BPA-13; D35, D36, D38, D61, D71, D72): replaces a Work Pack's Worker list with the rows of a
 * replacement file, all or nothing for that Work Pack. The rows are checked with BPA-22's rules ({@link AssignmentRowValidator}). If any row is
 * rejected, nothing changes and every row of the Work Pack reads "Replacement not applied: file has rejected rows". Otherwise
 * {@link ReplacementPlanner} decides what happens to each Worker (the rule is in the BPA-13 plan), the assignments, the history, the Journal
 * Lines and the time sheets are brought in line, and the accepted rows read "Assigned". On both outcomes the Work Pack goes from Replacing back to
 * Assigned (D61). One transaction with the Work Pack row locked. A retried job finds no row still "Pending" and answers the same without writing
 * (D65, 09 S7). Who did it (D63): the Camunda username that uploaded the replacement file; the time is the injected clock (09 S9).
 */
@Service
public class WorkerReplacementService {

    static final String PENDING = "Pending";
    static final String REPLACING = "Replacing";
    static final String SERVICE_USER = "bpa-service";

    private static final Logger LOG = LoggerFactory.getLogger(WorkerReplacementService.class);

    /** The Work Pack is not Replacing, so its Pending rows must not be applied (D61): the job fails with this text, no retries. */
    public static class NotReplacingException extends RuntimeException {
        NotReplacingException(String message) {
            super(message);
        }
    }

    private final WorkerReplacementRepository replacements;
    private final WorkerAssignmentRepository assignments;
    private final HourJournalLineRepository lines;
    private final DigitalTimesheetRepository sheets;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public WorkerReplacementService(WorkerReplacementRepository replacements, WorkerAssignmentRepository assignments, HourJournalLineRepository lines,
            DigitalTimesheetRepository sheets, TransactionTemplate transaction, Clock clock) {
        this.replacements = replacements;
        this.assignments = assignments;
        this.lines = lines;
        this.sheets = sheets;
        this.transaction = transaction;
        this.clock = clock;
    }

    /** Replaces the assignment of the Work Pack from the file's rows; true when the replacement was applied, the value of replacementApplied. */
    public boolean replace(String workpackNumber, UUID fileId) {
        Boolean applied = transaction.execute(status -> {
            var workPack = replacements.lock(workpackNumber).orElseThrow(() -> new IllegalStateException("No Work Pack " + workpackNumber));
            List<FileRow> pending = assignments.rows(fileId, workpackNumber).stream().filter(r -> PENDING.equals(r.outcome())).toList();
            if (pending.isEmpty()) {
                boolean answered = !replacements.wasNotApplied(fileId, workpackNumber); // a retried job: already answered (D65)
                LOG.info("{}: file {} has no Pending row for this Work Pack (a retried job); answering {}", workpackNumber, fileId, answered);
                return answered;
            }
            if (!REPLACING.equals(workPack.state())) {
                throw new NotReplacingException(workpackNumber + " is " + (workPack.state().isEmpty() ? "without an assignment state" : workPack.state())
                        + ", not Replacing, so its replacement cannot be applied (D61)");
            }
            String username = assignments.uploader(fileId).orElse(SERVICE_USER);
            Instant at = clock.instant().truncatedTo(ChronoUnit.MICROS);
            Set<String> badges = pending.stream().map(FileRow::badgeNo).filter(b -> b != null && !b.isBlank()).map(String::strip).collect(Collectors.toSet());
            Map<String, UUID> workers = assignments.workersByBadge(badges);
            List<Verdict> verdicts = AssignmentRowValidator.validate(
                    pending.stream().map(r -> new Row(r.rowNumber(), r.badgeNo(), r.estimatedHoursRaw(), r.remarks())).toList(), workers.keySet());

            if (verdicts.stream().anyMatch(v -> !v.accepted())) {
                for (int i = 0; i < pending.size(); i++) {
                    Verdict verdict = verdicts.get(i);
                    replacements.markNotApplied(pending.get(i).id(), verdict.accepted() ? null : verdict.outcome(), username, at);
                }
                replacements.backToAssigned(workPack.id(), username, at);
                return false;
            }

            List<Assigned> current = replacements.assignments(workPack.id());
            Map<UUID, Assigned> byWorker = new HashMap<>();
            current.forEach(a -> byWorker.put(a.workerId(), a));
            List<Wanted> wanted = new java.util.ArrayList<>();
            for (int i = 0; i < pending.size(); i++) {
                String badge = pending.get(i).badgeNo().strip();
                wanted.add(new Wanted(workers.get(badge), badge, verdicts.get(i).hours(), pending.get(i).remarks()));
            }
            Map<UUID, Wanted> wantedByWorker = new HashMap<>();
            wanted.forEach(w -> wantedByWorker.put(w.workerId(), w));
            String fileName = replacements.fileName(fileId);

            List<Step> steps = ReplacementPlanner.plan(
                    current.stream().map(a -> new Current(a.workerId(), a.badgeNo(), a.hours(), a.hoursLoaded())).toList(), wanted);
            for (Step step : steps) {
                Assigned old = byWorker.get(step.workerId());
                switch (step.kind()) {
                    case ADDED -> assignments.insert(workPack.id(), step.workerId(), step.newHours(), wantedByWorker.get(step.workerId()).remarks(), fileId, username, at)
                            .ifPresent(id -> replacements.addHistory(id, workPack.id(), step.workerId(), BigDecimal.ZERO, step.newHours(), "Added", fileName, fileId, username, at));
                    case REPLACED -> {
                        replacements.change(old, step.newHours(), wantedByWorker.get(step.workerId()).remarks(), fileId, username, at);
                        replacements.addHistory(old.id(), workPack.id(), step.workerId(), step.oldHours(), step.newHours(), "Replaced", fileName, fileId, username, at);
                    }
                    case UNCHANGED -> replacements.change(old, step.newHours(), wantedByWorker.get(step.workerId()).remarks(), fileId, username, at);
                    case REMOVED -> {
                        replacements.addHistory(old.id(), workPack.id(), step.workerId(), step.oldHours(), BigDecimal.ZERO, "Removed", fileName, fileId, username, at);
                        replacements.delete(workPack.id(), old, username, at);
                    }
                    case KEPT_FLAGGED -> {
                        replacements.flagHoursLoaded(old, username, at);
                        replacements.addHoursLoadedRow(fileId, workpackNumber, step.badgeNo(), username, at);
                    }
                }
            }
            lines.syncWithAssignments(workPack.id(), username, at);
            sheets.syncWithLines(workPack.id(), username, at);
            for (FileRow row : pending) {
                assignments.setOutcome(row.id(), AssignmentRowValidator.ASSIGNED, username, at);
            }
            replacements.backToAssigned(workPack.id(), username, at);
            return true;
        });
        return Boolean.TRUE.equals(applied);
    }
}
