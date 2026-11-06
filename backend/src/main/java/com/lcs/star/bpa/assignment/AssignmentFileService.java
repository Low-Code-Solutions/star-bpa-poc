package com.lcs.star.bpa.assignment;

import com.lcs.star.bpa.adapter.CamundaProcessClient;
import com.lcs.star.bpa.adapter.CamundaTaskClient.EngineUnavailableException;
import com.lcs.star.bpa.assignment.AssignmentFileReader.Row;
import com.lcs.star.bpa.persistence.AssignmentFileRepository;
import com.lcs.star.bpa.persistence.AssignmentFileRepository.WorkPackState;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The upload of a Worker assignment file (BPA-12; D31, D33, D49, D50, D58, D61, D62, D73): records the upload and every row, checks
 * the file, and routes each Work Pack group in it by the Work Pack's state. Row validation is BPA-22.
 *
 * <p>Per Work Pack group, in this order: no number; unknown Work Pack; already published for this file content (D73); not Released;
 * Released with no print yet (D50); Assigning; Replacing; Assigned (a replacement, BPA-13); AwaitingFile, which is claimed in
 * {@code published_message} and, once everything is committed, sent as AssignmentFileReceived with the synchronous correlate call
 * (C3). If nothing correlates, the claim is deleted and the rows read "Not processed: Work Pack not ready; resend the file" (C2,
 * D62). A file in which every Work Pack was published before is "File already processed" (D73). The intake (the file, its rows and the
 * claims) is one transaction; the send comes after the commit, so a crash between them loses a send, not a record (at-most-once,
 * data model 5.12).
 */
@Service
public class AssignmentFileService {

    static final String PENDING = "Pending";
    static final String ALREADY_PROCESSED_ROW = "Already processed in an earlier upload";
    static final String FILE_ALREADY_PROCESSED = "File already processed";
    static final String NOT_PROCESSED = "Not processed: Work Pack not ready; resend the file";
    static final String MESSAGE = "AssignmentFileReceived";

    private static final Logger LOG = LoggerFactory.getLogger(AssignmentFileService.class);

    private final AssignmentFileRepository repository;
    private final CamundaProcessClient camunda;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public AssignmentFileService(AssignmentFileRepository repository, CamundaProcessClient camunda, TransactionTemplate transaction, Clock clock) {
        this.repository = repository;
        this.camunda = camunda;
        this.transaction = transaction;
        this.clock = clock;
    }

    /** Records the upload and routes it; the id of the new assignment_file row (its result is read back from the tables). */
    public UUID upload(String fileName, byte[] file, String username) {
        // Microseconds, as the column holds them, so what is recorded is what a re-read gives.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String sha256 = sha256(file);
        var parsed = AssignmentFileReader.read(file);
        if (parsed.fileOutcome() != null) {
            return transaction.execute(status -> repository.saveFile(fileName, sha256, username, now, parsed.fileOutcome()));
        }

        Intake intake = transaction.execute(status -> intake(fileName, sha256, username, now, parsed.rows()));
        for (String workpackNumber : intake.claimed()) {
            if (!delivered(workpackNumber, intake.fileId())) {
                transaction.executeWithoutResult(status -> {
                    repository.unclaim(sha256, workpackNumber);
                    repository.setPendingRowsOutcome(intake.fileId(), workpackNumber, NOT_PROCESSED);
                });
            }
        }
        return intake.fileId();
    }

    private record Intake(UUID fileId, List<String> claimed) {
    }

    private Intake intake(String fileName, String sha256, String username, Instant now, List<Row> rows) {
        Map<String, List<Row>> groups = new LinkedHashMap<>();
        for (Row row : rows) {
            groups.computeIfAbsent(row.workpackNumber() == null ? "" : row.workpackNumber(), k -> new ArrayList<>()).add(row);
        }
        Map<String, String> outcomes = new LinkedHashMap<>();
        List<String> claimed = new ArrayList<>();
        for (String workpackNumber : groups.keySet()) {
            outcomes.put(workpackNumber, decide(workpackNumber, sha256, username, now, claimed));
        }
        // D73: the file-level line only when every Work Pack in the file was published before.
        boolean allDuplicates = outcomes.values().stream().allMatch(ALREADY_PROCESSED_ROW::equals);
        if (allDuplicates) {
            return new Intake(repository.saveFile(fileName, sha256, username, now, FILE_ALREADY_PROCESSED), List.of());
        }
        UUID fileId = repository.saveFile(fileName, sha256, username, now, null);
        for (Row row : rows) {
            String key = row.workpackNumber() == null ? "" : row.workpackNumber();
            repository.saveRow(fileId, row.rowNumber(), row.workpackNumber(), row.badgeNo(), row.estimatedHours(), row.remarks(), outcomes.get(key),
                    username, now);
        }
        return new Intake(fileId, claimed);
    }

    /** The outcome of one Work Pack group; for a group that will be sent, the pair is claimed here and the Work Pack added to {@code claimed}. */
    private String decide(String workpackNumber, String sha256, String username, Instant now, List<String> claimed) {
        if (workpackNumber.isEmpty()) {
            return "Workpack number is required"; // data model 7.3: an intake outcome, since no instance would ever see the row
        }
        WorkPackState state = repository.workPack(workpackNumber).orElse(null);
        if (state == null) {
            return "Work Pack not found";
        }
        // D73 before the state: a Work Pack published from this very file may already have moved on to Assigning.
        if (repository.published(sha256, workpackNumber)) {
            return ALREADY_PROCESSED_ROW;
        }
        if (!"Released".equals(state.status())) {
            return "Work Pack is not Released";
        }
        if (state.assignmentState() == null) {
            return "Work Pack not ready for assignment yet"; // Released, but the print is not generated yet (D50)
        }
        switch (state.assignmentState()) {
            case "Assigning":
                return "Assignment still in progress; try again later";
            case "Replacing":
                return "Replacement already in progress";
            case "AwaitingFile":
                if (!repository.claim(sha256, workpackNumber, username, now)) {
                    return ALREADY_PROCESSED_ROW; // a concurrent identical upload claimed the pair first
                }
                claimed.add(workpackNumber);
                return PENDING;
            default:
                // Assigned: a replacement is BPA-13's. Until it exists the rows say so (ASSUMPTION text).
                return "Replacement is not available yet";
        }
    }

    /** Sends AssignmentFileReceived; false when no instance received it, or Camunda could not be reached (ASSUMPTION: the user resends). */
    private boolean delivered(String workpackNumber, UUID fileId) {
        try {
            boolean correlated = camunda.correlate(MESSAGE, workpackNumber, Map.of("assignmentFileId", fileId.toString()));
            if (!correlated) {
                LOG.info("{} for {} (file {}): no instance was waiting for it", MESSAGE, workpackNumber, fileId);
            }
            return correlated;
        } catch (EngineUnavailableException e) {
            LOG.warn("{} for {} (file {}) could not be sent: {}", MESSAGE, workpackNumber, fileId, e.getMessage());
            return false;
        } catch (RuntimeException e) {
            // Any other failure of the send (a refusal, a bad answer) is "not delivered" too: the claim is undone and the loop goes on
            // with the next Work Pack, so a failed send never leaves a claim that blocks a resend for ever (data model 5.12; found in review).
            LOG.error("{} for {} (file {}) failed: {}", MESSAGE, workpackNumber, fileId, e.toString(), e);
            return false;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
