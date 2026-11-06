package com.lcs.star.bpa.assignment;

import com.lcs.star.bpa.domain.AssignmentRowValidator;
import com.lcs.star.bpa.domain.AssignmentRowValidator.Row;
import com.lcs.star.bpa.domain.AssignmentRowValidator.Verdict;
import com.lcs.star.bpa.persistence.WorkerAssignmentRepository;
import com.lcs.star.bpa.persistence.WorkerAssignmentRepository.FileRow;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * The work of {@code validate-worker-assignment} (BPA-22): checks the rows an upload routed to this Work Pack, saves the accepted Workers in
 * {@code worker_assignment}, writes every row's outcome so the result screen shows it (D49, D58), and moves the Work Pack's
 * assignment_state (D61): AwaitingFile to Assigning when at least one row is accepted; when every row is rejected nothing is saved and the
 * state stays AwaitingFile, so the instance can wait for a corrected file (D5). One transaction with the Work Pack row locked.
 *
 * <p>A retried job finds no row still "Pending" and answers the same count without writing anything (D65, 09 S7). Who did it (D63): the
 * Camunda username that uploaded the file, written to created_by, updated_by and audit_log; the time is the injected clock (09 S9).
 */
@Service
public class WorkerAssignmentService {

    static final String PENDING = "Pending";
    static final String AWAITING_FILE = "AwaitingFile";
    static final String SERVICE_USER = "bpa-service";

    private static final Logger LOG = LoggerFactory.getLogger(WorkerAssignmentService.class);

    /** The Work Pack is not waiting for a file, so its Pending rows must not be validated (D61): the job fails with this text, no retries. */
    public static class NotAwaitingFileException extends RuntimeException {
        NotAwaitingFileException(String message) {
            super(message);
        }
    }

    private final WorkerAssignmentRepository repository;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public WorkerAssignmentService(WorkerAssignmentRepository repository, TransactionTemplate transaction, Clock clock) {
        this.repository = repository;
        this.transaction = transaction;
        this.clock = clock;
    }

    /** Validates the file's rows for the Work Pack; returns how many Workers it has from this file, the value of assignedWorkerCount. */
    public int validate(UUID workpackId, String workpackNumber, UUID fileId) {
        Integer count = transaction.execute(status -> {
            String state = repository.lockState(workpackId).orElseThrow(() -> new IllegalStateException("No Work Pack " + workpackNumber));
            List<FileRow> pending = repository.rows(fileId, workpackNumber).stream().filter(r -> PENDING.equals(r.outcome())).toList();
            if (pending.isEmpty()) {
                int already = repository.assignedFrom(workpackId, fileId); // a retried job: already answered (D65)
                LOG.info("{}: file {} has no Pending row for this Work Pack (a retried job); answering {}", workpackNumber, fileId, already);
                return already;
            }
            if (!AWAITING_FILE.equals(state)) {
                throw new NotAwaitingFileException(workpackNumber + " is " + (state.isEmpty() ? "without an assignment state" : state)
                        + ", not AwaitingFile, so its assignment rows cannot be validated (D61)");
            }
            String username = repository.uploader(fileId).orElse(SERVICE_USER);
            Instant at = clock.instant().truncatedTo(ChronoUnit.MICROS);
            Set<String> badges = pending.stream().map(FileRow::badgeNo).filter(b -> b != null && !b.isBlank()).map(String::strip)
                    .collect(Collectors.toSet());
            Map<String, UUID> workers = repository.workersByBadge(badges);
            List<Verdict> verdicts = AssignmentRowValidator.validate(
                    pending.stream().map(r -> new Row(r.rowNumber(), r.badgeNo(), r.estimatedHoursRaw(), r.remarks())).toList(), workers.keySet());
            int accepted = 0;
            for (int i = 0; i < pending.size(); i++) {
                FileRow row = pending.get(i);
                Verdict verdict = verdicts.get(i);
                if (verdict.accepted()) {
                    // A Worker who already holds this Work Pack cannot be assigned from another file (D32: once per Work Pack). A re-run is
                    // caught above (no Pending row), so this is a state no path creates yet: fail the job and roll back rather than read "Assigned".
                    if (!repository.assign(workpackId, workers.get(row.badgeNo().strip()), verdict.hours(), row.remarks(), fileId, username, at)) {
                        throw new IllegalStateException(workpackNumber + ": Worker " + row.badgeNo().strip()
                                + " is already assigned to this Work Pack, so file " + fileId + " cannot assign it again (D32)");
                    }
                    accepted++;
                }
                repository.setOutcome(row.id(), verdict.outcome(), username, at);
            }
            if (accepted > 0) {
                repository.setAssigning(workpackId, username, at);
            }
            return accepted;
        });
        return count == null ? 0 : count;
    }
}
