package com.lcs.star.bpa.progress;

import org.springframework.stereotype.Component;

/**
 * BPA-65 red stub: the daily close cost check (FRD-22 §22.10; plan Q1 option A). The real job is a {@code @Scheduled} method at 06:00 Asia/Riyadh that checks every CBS
 * with Decision_CostVariance during the close window and alerts through the shared sender. This stub does nothing, so the red tests compile and fail on their assertions.
 */
@Component
public class DailyCloseCostCheckJob {

    public void run() {
        // red stub: the implementation replaces this
    }
}
