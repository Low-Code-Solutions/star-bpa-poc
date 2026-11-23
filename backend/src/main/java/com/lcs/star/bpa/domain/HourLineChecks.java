package com.lcs.star.bpa.domain;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.Optional;

/**
 * The pure rules of an hour line (BPA-50; Gherkin texts exact): each check answers the message that refuses the value, or nothing. The service
 * (HourLineService) decides the order, which is an ASSUMPTION of the plan; every check here is also backed by a table CHECK or the partial
 * unique index where the data model has one (§13.1).
 */
public final class HourLineChecks {

    public static final int MAX_REMARKS = 250;
    private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private HourLineChecks() {
    }

    /** "17 Nov 2026", the way the story writes dates in its messages. */
    public static String show(LocalDate date) {
        return LONG_DATE.format(date);
    }

    public static Optional<String> remarks(String remarks) {
        return remarks != null && remarks.length() > MAX_REMARKS ? Optional.of("Remarks can be at most 250 characters") : Optional.empty();
    }

    /** Above 0 first, then at most 2 decimals (numeric(7,2) would round, so the API refuses). */
    public static Optional<String> hours(BigDecimal hours) {
        if (hours.signum() <= 0) {
            return Optional.of("Hours must be greater than 0");
        }
        // hours is numeric(7,2): more than 5 digits before the point cannot be stored, and a value like 1E999999999 would make BigDecimal.add build a
        // number of a billion digits when the budget sums are formed (BPA-52 review). Not a number hours can be.
        if (hours.precision() - hours.scale() > 5) {
            return Optional.of("Hours must be a number");
        }
        if (hours.stripTrailingZeros().scale() > 2) {
            return Optional.of("Hours can have at most 2 decimal places");
        }
        return Optional.empty();
    }

    /** Reported completion % (C-E2-10): 0 to 100, 2 decimals. The text is an ASSUMPTION; no story states it. */
    public static Optional<String> completion(BigDecimal pct) {
        if (pct == null) {
            return Optional.empty();
        }
        boolean outside = pct.signum() < 0 || pct.compareTo(BigDecimal.valueOf(100)) > 0 || pct.stripTrailingZeros().scale() > 2;
        return outside ? Optional.of("Reported completion must be between 0 and 100") : Optional.empty();
    }

    public static Optional<String> notInTheFuture(LocalDate workDate, LocalDate today) {
        return workDate.isAfter(today) ? Optional.of("Work date cannot be in the future") : Optional.empty();
    }

    /** The Thursday on or after the date: the week ends on Thursday (E2-D15; day and time are an ASSUMPTION of the epic). */
    public static LocalDate weekEnding(LocalDate date) {
        return date.with(TemporalAdjusters.nextOrSame(DayOfWeek.THURSDAY));
    }

    public static String weekClosed(LocalDate weekEnding) {
        return "Week ending " + show(weekEnding) + " is closed";
    }

    private static final DateTimeFormatter MONTH_YEAR = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    /** BPA-67 (Jira, exact): a Completed Work Pack takes no more hours. */
    public static String completedMessage(String workpackNumber) {
        return "Work Pack " + workpackNumber + " is Completed";
    }

    /** BPA-67 (S5; Jira, exact): the hours of a locked month cannot be added, changed or approved, for example "Hours for November 2026 are locked". */
    public static String monthLockedMessage(LocalDate workDate) {
        return "Hours for " + MONTH_YEAR.format(workDate) + " are locked";
    }

    /**
     * First day of the open week: the Friday that starts the week containing today, or the day after the latest cut-off week when that is later
     * (ASSUMPTION: nothing seeds progress_period, so "open" means "no cut-off row yet", plan Q4).
     */
    public static LocalDate openWeekStart(LocalDate today, Optional<LocalDate> latestClosedWeekEnding) {
        LocalDate start = weekEnding(today).minusDays(6);
        return latestClosedWeekEnding.map(closed -> closed.plusDays(1)).filter(afterClosed -> afterClosed.isAfter(start)).orElse(start);
    }

    /**
     * DAILY_LIMIT_EXCEEDED: the text at save and at send (BPA-51; data model §13.2.3, marked (A) there). The limit is the setting
     * app.daily-hours-limit, 16 by default.
     */
    public static String dailyLimitMessage(String badge, LocalDate date, BigDecimal dailyLimit) {
        return "Worker " + badge + " would exceed " + plain(dailyLimit) + " hours on " + show(date);
    }

    /** CBS_CLOSED: the text at save (form and upload row) and at send (BPA-69; Jira, marked ASSUMPTION there). */
    public static String cbsClosedMessage(String cbsCodeRef) {
        return "CBS " + cbsCodeRef + " is closed for productive hours";
    }

    /** WORKER_ON_LEAVE: the text at save (form and upload row) and at send (BPA-75; Jira). The date is the work date, a business-zone date (ADR 0013). */
    public static String workerOnLeaveMessage(String badge, LocalDate date) {
        return "Worker " + badge + " is on leave on " + show(date);
    }

    /** PHASE_BUDGET_EXCEEDED: the text at save and at send; the budget is workpack_phase.manhours. */
    public static String phaseBudgetMessage(String phase, BigDecimal budget) {
        return "Phase " + phase + " budget of " + plain(budget) + " h would be exceeded";
    }

    /** A plain decimal for messages and the audit log: 8 not 8.00, 9.5 not 9.50 (data model §6.1). */
    public static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
