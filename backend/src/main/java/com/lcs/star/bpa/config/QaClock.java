package com.lcs.star.bpa.config;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/** BPA-81 stub, compile-only: the QA business date. Not built yet. */
final class QaClock {

    private QaClock() {
    }

    static LocalDate parse(String raw) {
        throw new UnsupportedOperationException("BPA-81: not built yet");
    }

    static Clock offsetTo(LocalDate target, Clock base, ZoneId zone) {
        throw new UnsupportedOperationException("BPA-81: not built yet");
    }
}
