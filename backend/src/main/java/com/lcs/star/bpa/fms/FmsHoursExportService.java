package com.lcs.star.bpa.fms;

import com.lcs.star.bpa.fms.FmsHoursFileWriter.FmsRow;
import com.lcs.star.bpa.persistence.FmsHoursExportRepository;
import com.lcs.star.bpa.persistence.FmsHoursExportRepository.Export;
import com.lcs.star.bpa.persistence.FmsHoursExportRepository.HandOff;
import com.lcs.star.bpa.persistence.FmsHoursExportRepository.WaitingLine;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The weekly FMS hours files and their hand-off (BPA-58; E2-08). {@link #generate} selects every Approved line not yet exported, writes one file per
 * calendar month of the work dates (S5), stores them and stamps the lines, in one transaction; a re-run for the same period returns the files it made and
 * writes nothing (re-run key: the progress period, ADR 0007). {@link #recordHandOffs} writes PPC's outcome per file: Sent (who and when) or Rejected by FMS
 * (reason; the file's lines are released for the next file, the file is kept, S4); a file that already has an outcome is left as it is.
 */
@Service
public class FmsHoursExportService {

    public static final String SENT = "Sent to FMS";
    public static final String REJECTED = "Rejected by FMS";

    private final FmsHoursExportRepository exports;

    public FmsHoursExportService(FmsHoursExportRepository exports) {
        this.exports = exports;
    }

    /** One outcome of the Send hours to FMS task; {@code reason} is only read for Rejected by FMS. */
    public record Outcome(UUID exportId, String outcome, String reason) {
    }

    @Transactional
    public List<Export> generate(UUID periodId, LocalDate weekEnding, String username) {
        List<Export> existing = exports.ofPeriod(periodId);
        if (!existing.isEmpty()) {
            return existing;
        }
        Map<YearMonth, List<WaitingLine>> byMonth = new LinkedHashMap<>();
        for (WaitingLine line : exports.waitingUpTo(weekEnding)) {
            byMonth.computeIfAbsent(YearMonth.from(line.row().workDate()), m -> new ArrayList<>()).add(line);
        }
        if (byMonth.isEmpty()) {
            byMonth.put(YearMonth.from(weekEnding), List.of()); // a week with no approved lines still gets a file with headers only (Q-DM19)
        }
        List<Export> made = new ArrayList<>();
        for (Map.Entry<YearMonth, List<WaitingLine>> month : byMonth.entrySet()) {
            List<FmsRow> rows = month.getValue().stream().map(WaitingLine::row).toList();
            byte[] file = FmsHoursFileWriter.write(rows);
            LocalDate first = month.getKey().atDay(1);
            UUID id = exports.insert(periodId, first, file, sha256(file), rows.size(), username);
            exports.stamp(month.getValue().stream().map(WaitingLine::lineId).toList(), id, username);
            made.add(new Export(id, first, rows.size()));
        }
        return made;
    }

    /** Applies each outcome to its file; an unknown file is a fault (the task API only lets a task's own files through). */
    @Transactional
    public void recordHandOffs(List<Outcome> outcomes, String username) {
        for (Outcome outcome : outcomes) {
            HandOff state = exports.handOff(outcome.exportId())
                    .orElseThrow(() -> new IllegalArgumentException("FMS hours file " + outcome.exportId() + " not found"));
            if (state.sentAt() != null || state.rejectedAt() != null) {
                continue; // re-run: the outcome is recorded already
            }
            if (SENT.equals(outcome.outcome())) {
                exports.recordSent(outcome.exportId(), username);
            } else if (REJECTED.equals(outcome.outcome()) && outcome.reason() != null && !outcome.reason().isBlank()) {
                exports.recordRejected(outcome.exportId(), outcome.reason().trim(), username);
            } else {
                throw new IllegalArgumentException("Unknown outcome " + outcome.outcome() + " for FMS hours file " + outcome.exportId());
            }
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
