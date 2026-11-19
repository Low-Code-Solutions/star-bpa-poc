package com.lcs.star.bpa.progress;

import com.lcs.star.bpa.config.ProgressSettings;
import com.lcs.star.bpa.fileintake.TabularFileReader;
import com.lcs.star.bpa.fileintake.TabularFileReader.TabularRow;
import com.lcs.star.bpa.persistence.CompletionReportRepository;
import com.lcs.star.bpa.persistence.ProgressPeriodRepository;
import com.lcs.star.bpa.persistence.ProgressPeriodRepository.Period;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.ErrorResponseException;

/**
 * The completion report upload (BPA-59; E2-09; plan section 3): reads the file with the shared {@link TabularFileReader} (BPA-52's rules: .xlsx or .csv,
 * headers by name in any order and case), logs it with its sha256 and checks every row on its own, in file order ({@link CompletionRowChecks}), logging
 * each row as written with its outcome. Nothing is applied to the week's progress here: the Read completion report step does that when PPC completes the
 * task with the report that counts (the first upload of a task may be replaced by a later one, Q-DM20). The report belongs to the week of the instance it
 * was uploaded into (decision (d)): the caller gives the instance of the open Upload completion report task. One transaction for the report and its rows.
 */
@Service
public class CompletionReportService {

    static final List<String> REQUIRED = List.of("workpack number", "completion %", "source");
    static final List<String> OPTIONAL = List.of("actual hours");

    private final CompletionReportRepository reports;
    private final ProgressPeriodRepository periods;
    private final ProgressSettings settings;

    public CompletionReportService(CompletionReportRepository reports, ProgressPeriodRepository periods, ProgressSettings settings) {
        this.reports = reports;
        this.periods = periods;
        this.settings = settings;
    }

    /** @return the id of the logged report */
    @Transactional
    public UUID upload(long processInstanceKey, String fileName, byte[] file, String username) {
        Period period = periods.ofInstance(processInstanceKey).orElseThrow(() -> new ErrorResponseException(HttpStatus.CONFLICT,
                ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "The week of this task has no progress period"), null)); // ASSUMPTION text
        TabularFileReader.Result read = TabularFileReader.read(fileName, file, REQUIRED, OPTIONAL, true); // the cell value, not its display (QA review)
        UUID id = reports.insertReport(period.id(), fileName, sha256(file), username, read.fileOutcome());
        String configured = settings.source();
        Set<String> seen = new HashSet<>();
        for (TabularRow row : read.rows()) {
            CompletionRowChecks.Raw raw = new CompletionRowChecks.Raw(row.get("workpack number"), row.get("completion %"), row.get("actual hours"),
                    row.get("source"));
            String outcome = CompletionRowChecks.check(raw, seen, reports::workPackStatus, configured);
            Optional<String> source = CompletionRowChecks.knownSource(raw.source());
            reports.insertRow(id, row.rowNumber(), raw.workpackNumber(), raw.completion(), raw.actualHours(), raw.source(), source.orElse(null), outcome, username);
        }
        return id;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
