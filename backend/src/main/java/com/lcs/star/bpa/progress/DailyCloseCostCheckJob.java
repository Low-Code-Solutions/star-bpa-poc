package com.lcs.star.bpa.progress;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The daily trigger of the close cost check (BPA-65; plan Q1 option A, like {@link MonthLockJob}): at 06:00 Asia/Riyadh it asks {@link DailyCloseCostCheckService} to check the
 * cost of every CBS if the business date is inside the close window. The date comes from the injected clock (ADR 0013), so the tests set the clock and call {@link #run()}. There are no
 * Camunda retries outside a process (ASSUMPTION): a failure (engine, mail, nobody to alert) is logged as an error and the job ends for the day; the alerts already recorded stay, and
 * the next day or the weekly check alerts again.
 */
@Component
public class DailyCloseCostCheckJob {

    private static final Logger LOG = LoggerFactory.getLogger(DailyCloseCostCheckJob.class);

    private final DailyCloseCostCheckService check;
    private final Clock clock;
    private final ZoneId zone;

    public DailyCloseCostCheckJob(DailyCloseCostCheckService check, Clock clock, ZoneId businessZone) {
        this.check = check;
        this.clock = clock;
        this.zone = businessZone;
    }

    @Scheduled(cron = "0 0 6 * * *", zone = "Asia/Riyadh")
    public void run() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        try {
            check.runFor(today);
        } catch (RuntimeException e) {
            LOG.error("Daily close cost check for {} failed: {}", today, e.getMessage(), e);
        }
    }
}
