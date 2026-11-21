package com.lcs.star.bpa.progress;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * BPA-64 (D5): stub in the contract and red-test commit; the implementation (the month lock and the frozen man-hour snapshot) replaces it. Locks the previous
 * month when the business date has reached the lock day and returns that month's first day, or empty when none is due or it is already locked.
 */
@Service
public class MonthLockService {

    public Optional<LocalDate> lockDueMonth(LocalDate businessDate, String username) {
        throw new UnsupportedOperationException("BPA-64: the month lock is not built yet");
    }
}
