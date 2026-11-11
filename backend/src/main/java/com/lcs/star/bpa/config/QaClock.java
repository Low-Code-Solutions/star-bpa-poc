package com.lcs.star.bpa.config;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * The QA business date (BPA-81; ADR 0013, 09 S9). Pure helpers for {@link ClockConfig}: reading {@code BPA_CLOCK_TODAY} and making a clock whose
 * business date, read in the business zone, is that date. The offset is whole days, worked out once from the base clock, so the time of day is kept
 * and keeps moving (audit timestamps stay ordered). After midnight in the zone the date rolls on like a real clock. The offset is exact for a zone
 * without daylight saving (Asia/Riyadh, 09 S9); in a zone with DST that changes between today and the target, the local time shifts by that hour.
 */
final class QaClock {

    static final String VARIABLE = "BPA_CLOCK_TODAY";

    private QaClock() {
    }

    /** The date in the form yyyy-MM-dd (ISO 8601, strict: 2026-02-30 and 2026-1-1 are refused); white space around it is ignored. */
    static LocalDate parse(String raw) {
        String value = raw == null ? "" : raw.trim();
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            // no cause chained: the parser's own text ("Invalid date 'FEBRUARY 30'") would become the root cause an operator reads, and it names neither the variable nor the value
            throw new IllegalStateException(VARIABLE + " must be a date in the form yyyy-MM-dd (ISO 8601); received '" + value + "'");
        }
    }

    /** Whole days between the base clock's business date in {@code zone} and {@code target} (negative for a past date). */
    static long days(LocalDate target, Clock base, ZoneId zone) {
        return ChronoUnit.DAYS.between(LocalDate.ofInstant(base.instant(), zone), target);
    }

    /** A clock that runs {@link #days} from {@code base}: its business date in {@code zone} is {@code target} now, and time keeps moving. */
    static Clock offsetTo(LocalDate target, Clock base, ZoneId zone) {
        return Clock.offset(base, Duration.ofDays(days(target, base, zone)));
    }
}
