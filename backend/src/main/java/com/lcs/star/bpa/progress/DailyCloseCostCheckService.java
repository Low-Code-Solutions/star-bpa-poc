package com.lcs.star.bpa.progress;

import java.time.LocalDate;
import org.springframework.stereotype.Service;

/**
 * BPA-65 red stub: the daily close cost check (FRD-22 §22.10; plan Q1 option A). {@link #inCloseWindow} says whether a date is inside the close (from three days before the
 * month lock day up to and including it; an ASSUMPTION of the story); {@link #runFor} checks every CBS with Decision_CostVariance and alerts. Both are built after their tests
 * are shown red.
 */
@Service
public class DailyCloseCostCheckService {

    public static boolean inCloseWindow(LocalDate date, int monthLockDay) {
        throw new UnsupportedOperationException("BPA-65: not built yet");
    }

    public void runFor(LocalDate today) {
        throw new UnsupportedOperationException("BPA-65: not built yet");
    }
}
