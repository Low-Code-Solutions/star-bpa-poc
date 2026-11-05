package com.lcs.star.bpa.domain;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The save step's schedule rules (BPA-11; D9, D56, F10), pure so a unit test covers them. In this order:
 * <ol>
 *   <li>a missing date is required (ASSUMPTION texts: the story states none);</li>
 *   <li>each date earlier than today + lead time is "Date cannot be earlier than <date>" (D9; the rule applies to the planned start
 *       and the planned end, F10), once even if both fail;</li>
 *   <li>only when both dates are there and pass the lead time: an end before the start is "Planned end cannot be before planned
 *       start" (PO note on BPA-11, 28 Oct: the lead-time rule is checked before end &gt;= start).</li>
 * </ol>
 * "Today" is the Asia/Riyadh calendar day from the injected clock (D28, 09 S9), passed in by the worker. An end after the date to
 * complete is not an error (D26): the screen shows a notice and the save succeeds.
 */
public final class ScheduleValidator {

    static final String START_REQUIRED = "Planned start is required";
    static final String END_REQUIRED = "Planned end is required";
    static final String END_BEFORE_START = "Planned end cannot be before planned start";
    private static final DateTimeFormatter SHOWN = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private ScheduleValidator() {
    }

    /** The messages to return with SCHEDULE_INVALID, none when the dates are valid; each text once. */
    public static List<String> validate(LocalDate plannedStart, LocalDate plannedEnd, LocalDate today, int leadTimeDays) {
        List<String> messages = new ArrayList<>();
        if (plannedStart == null) {
            messages.add(START_REQUIRED);
        }
        if (plannedEnd == null) {
            messages.add(END_REQUIRED);
        }
        LocalDate earliest = today.plusDays(leadTimeDays);
        String tooEarly = "Date cannot be earlier than " + SHOWN.format(earliest);
        for (LocalDate date : new LocalDate[] {plannedStart, plannedEnd}) {
            if (date != null && date.isBefore(earliest) && !messages.contains(tooEarly)) {
                messages.add(tooEarly);
            }
        }
        if (messages.isEmpty() && plannedEnd.isBefore(plannedStart)) {
            messages.add(END_BEFORE_START);
        }
        return messages;
    }
}
