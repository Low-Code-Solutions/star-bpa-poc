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
    static final String REPLACED_MESSAGE = "AssignmentFileReplaced";
    static final String REPLACEMENT_IN_PROGRESS = "Replacement already in progress";
    static final String REPLACEMENT_NOT_STARTED = "Replacement could not be started; try again";

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
        for (Claim claim : intake.claimed()) {
            Delivery delivery = delivered(claim, intake.fileId());
            if (delivery != Delivery.DELIVERED) {
                try {
                    transaction.executeWithoutResult(status -> undo(claim, delivery, intake.fileId(), sha256, username));
                } catch (RuntimeException e) {
                    // One failed undo must not stop the others of a multi-Work Pack file (BPA-13 review); the log says which Work Pack to look at.
                    LOG.error("The start of {} (file {}) could not be undone: {}", claim.workpackNumber(), intake.fileId(), e.toString(), e);
                }
            }
        }
        return intake.fileId();
    }

    /**
     * Undoes the start of a routed Work Pack that was not delivered: the claim goes, so the file can be sent again, and the rows say why. A replacement also
     * goes back from Replacing to Assigned (C3, D75), but only while its rows still read Pending: if the worker has already answered them (the engine did start
     * the instance although the call looked failed), the replacement ran and nothing is undone (BPA-13 review).
     */
    private void undo(Claim claim, Delivery delivery, UUID fileId, String sha256, String username) {
        if (claim.replacement()) {
            if (!repository.hasPendingRows(fileId, claim.workpackNumber())) {
                LOG.warn("The replacement of {} (file {}) was answered although its start looked failed; nothing is undone", claim.workpackNumber(), fileId);
                return;
            }
            repository.revertReplacing(claim.workpackNumber(), username, clock.instant().truncatedTo(ChronoUnit.MICROS));
            repository.unclaim(sha256, claim.workpackNumber());
            repository.setPendingRowsOutcome(fileId, claim.workpackNumber(), delivery == Delivery.NOT_RECEIVED ? REPLACEMENT_IN_PROGRESS : REPLACEMENT_NOT_STARTED);
        } else {
            repository.unclaim(sha256, claim.workpackNumber());
            repository.setPendingRowsOutcome(fileId, claim.workpackNumber(), NOT_PROCESSED);
        }
    }

    /** A Work Pack whose rows were routed: a first assignment (AssignmentFileReceived) or a replacement of an Assigned one (AssignmentFileReplaced, BPA-13). */
    private record Claim(String workpackNumber, boolean replacement) {
    }

    private record Intake(UUID fileId, List<Claim> claimed) {
    }

    private Intake intake(String fileName, String sha256, String username, Instant now, List<Row> rows) {
        Map<String, List<Row>> groups = new LinkedHashMap<>();
        for (Row row : rows) {
            groups.computeIfAbsent(row.workpackNumber() == null ? "" : row.workpackNumber(), k -> new ArrayList<>()).add(row);
        }
        Map<String, String> outcomes = new LinkedHashMap<>();
        List<Claim> claimed = new ArrayList<>();
        // In Work Pack order, so two uploads that cover the same Work Packs take their locks in the same order (BPA-13 review).
        for (String workpackNumber : new java.util.TreeSet<>(groups.keySet())) {
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
    private String decide(String workpackNumber, String sha256, String username, Instant now, List<Claim> claimed) {
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
                return REPLACEMENT_IN_PROGRESS;
            case "AwaitingFile":
                if (!repository.claim(sha256, workpackNumber, username, now)) {
                    return ALREADY_PROCESSED_ROW; // a concurrent identical upload claimed the pair first
                }
                claimed.add(new Claim(workpackNumber, false));
                return PENDING;
            default:
                // Assigned: this file is a replacement of the Worker list (BPA-13, D35, D61). Claim the pair (D73), then Assigned -> Replacing by
                // compare-and-set; the message is sent after the commit (C3).
                if (!repository.claim(sha256, workpackNumber, username, now)) {
                    return ALREADY_PROCESSED_ROW;
                }
                if (!repository.startReplacing(workpackNumber, username, now)) {
                    repository.unclaim(sha256, workpackNumber); // the state changed under us: another upload is ahead
                    return REPLACEMENT_IN_PROGRESS;
                }
                claimed.add(new Claim(workpackNumber, true));
                return PENDING;
        }
    }

    /** What happened to the message of one routed Work Pack. */
    private enum Delivery { DELIVERED, NOT_RECEIVED, FAILED }

    /**
     * Sends AssignmentFileReceived (a first assignment) or AssignmentFileReplaced (a replacement) with the synchronous correlate call (C3): not received
     * when no instance took it (nothing waiting, or, for a replacement, one is already running for the key: the BPA-13 probe), failed when Camunda could not
     * be reached or refused (ASSUMPTION: the user resends).
     */
    private Delivery delivered(Claim claim, UUID fileId) {
        String message = claim.replacement() ? REPLACED_MESSAGE : MESSAGE;
        String number = claim.workpackNumber();
        Map<String, Object> variables = claim.replacement() ? Map.of("assignmentFileId", fileId.toString(), "workpackNumber", number)
                : Map.of("assignmentFileId", fileId.toString());
        try {
            boolean correlated = camunda.correlate(message, number, variables);
            if (!correlated) {
                LOG.info("{} for {} (file {}): no instance took it", message, number, fileId);
            }
            return correlated ? Delivery.DELIVERED : Delivery.NOT_RECEIVED;
        } catch (EngineUnavailableException e) {
            LOG.warn("{} for {} (file {}) could not be sent: {}", message, number, fileId, e.getMessage());
            return Delivery.FAILED;
        } catch (RuntimeException e) {
            // Any other failure of the send (a refusal, a bad answer) is "not delivered" too: the claim is undone and the loop goes on
            // with the next Work Pack, so a failed send never leaves a claim that blocks a resend for ever (data model 5.12; found in review).
            LOG.error("{} for {} (file {}) failed: {}", message, number, fileId, e.toString(), e);
            return Delivery.FAILED;
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
