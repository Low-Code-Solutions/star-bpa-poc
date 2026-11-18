package com.lcs.star.bpa.timesheet;

import com.lcs.star.bpa.persistence.DigitalTimesheetLineRepository;
import com.lcs.star.bpa.persistence.DigitalTimesheetLineRepository.Line;
import com.lcs.star.bpa.persistence.DigitalTimesheetLineRepository.Sheet;
import com.lcs.star.bpa.persistence.TimesheetFileRepository;
import com.lcs.star.bpa.timesheet.HourLineService.Refused;
import com.lcs.star.bpa.timesheet.TimesheetFileReader.Row;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The time sheet upload (BPA-52; E2-D4; ADR 0009 and D49 pattern): reads the file, logs it with its sha256 and checks every row on its own, in file order,
 * exactly as a form entry (plan: no second copy of a rule). A row that passes saves a Draft line with entry source Upload through
 * {@link HourLineService#add}, which applies BPA-50's line rules and BPA-51's daily and phase checks; each saved row commits on its own, so a later row
 * sees the earlier rows of the same file, and an existing line is never overwritten (the duplicate rule). A rejected row never stops the others. Before the
 * form's rules, in this order: the five required columns, a date and a number that parse, the Work Pack, the Worker's time sheet (assigned, not Cancelled),
 * and the Work Pack's hours not being in approval (ASSUMPTION, plan Q4). Every text is the data model's (§13.2.3) or the Gherkin's; two differ from the
 * form's own (Worker not assigned, Time sheet is Cancelled; plan Q7). An engine that cannot answer the budget decision stops the upload with
 * EngineUnavailableException (503); the rows before it are saved and logged.
 */
@Service
public class TimesheetFileService {

    static final String SAVED = "Saved";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT);

    private final TimesheetFileRepository files;
    private final DigitalTimesheetLineRepository lines;
    private final HourLineService lineService;
    private final Clock clock;

    public TimesheetFileService(TimesheetFileRepository files, DigitalTimesheetLineRepository lines, HourLineService lineService, Clock clock) {
        this.files = files;
        this.lines = lines;
        this.lineService = lineService;
        this.clock = clock;
    }

    /** Records the upload and returns the file's id; the result is read back from {@link TimesheetFileRepository#result}. */
    public UUID upload(String fileName, byte[] file, String username) {
        TimesheetFileReader.Result read = TimesheetFileReader.read(fileName, file);
        UUID fileId = files.insertFile(fileName, sha256(file), username, clock.instant(), read.fileOutcome());
        for (Row row : read.rows()) {
            Outcome outcome = check(row, fileId, username);
            files.insertRow(fileId, row, outcome.text(), outcome.lineId(), username, clock.instant());
        }
        return fileId;
    }

    private record Outcome(String text, UUID lineId) {
    }

    private Outcome check(Row row, UUID fileId, String username) {
        if (row.workpackNumber() == null) {
            return rejected("Workpack number is required");
        }
        if (row.badgeNo() == null) {
            return rejected("Badge No is required");
        }
        if (row.workDate() == null) {
            return rejected("Work date is required");
        }
        if (row.phase() == null) {
            return rejected("Phase is required");
        }
        if (row.hours() == null) {
            return rejected("Hours is required");
        }
        Optional<LocalDate> date = date(row.workDate());
        if (date.isEmpty()) {
            return rejected("Work date is not a valid date");
        }
        Optional<BigDecimal> hours = hours(row.hours());
        if (hours.isEmpty()) {
            return rejected("Hours must be a number");
        }
        Optional<UUID> workpack = files.workPackId(row.workpackNumber());
        if (workpack.isEmpty()) {
            return rejected("Work Pack not found");
        }
        Optional<UUID> timesheet = files.timesheetOf(workpack.get(), row.badgeNo());
        if (timesheet.isEmpty()) {
            return rejected("Worker not assigned to this Work Pack");
        }
        Sheet sheet = lines.sheet(timesheet.get()).orElseThrow();
        if (!lines.workerCanBook(workpack.get(), sheet.workerId())) {
            return rejected("Worker not assigned to this Work Pack");
        }
        if ("Cancelled".equals(sheet.submissionStatus())) {
            return rejected("Time sheet is Cancelled");
        }
        if (files.inApproval(workpack.get())) {
            return rejected("Hours for " + row.workpackNumber() + " are in approval; book them after the decision");
        }
        try {
            Line saved = lineService.add(timesheet.get(), date.get(), row.phase(), hours.get(), row.remarks(), null, fileId, username);
            return new Outcome(SAVED, saved.id());
        } catch (Refused refused) {
            return rejected(refused.getMessage());
        }
    }

    private static Outcome rejected(String text) {
        return new Outcome(text, null);
    }

    /** dd/mm/yyyy (the template's layout); one-digit day or month accepted (ASSUMPTION). */
    private static Optional<LocalDate> date(String text) {
        try {
            return Optional.of(LocalDate.parse(text, DATE));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static Optional<BigDecimal> hours(String text) {
        try {
            return Optional.of(new BigDecimal(text));
        } catch (NumberFormatException e) {
            return Optional.empty();
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
