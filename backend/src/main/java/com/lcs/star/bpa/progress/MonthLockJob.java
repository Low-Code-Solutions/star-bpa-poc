package com.lcs.star.bpa.progress;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The daily trigger of the month lock (BPA-64; plan Q1 option A): at 00:10 Asia/Riyadh it asks {@link MonthLockService} to lock the previous month if the lock day has
 * been reached. The business date comes from the injected clock (ADR 0013). Running it again the same day does nothing: the month is locked once.
 */
@Component
public class MonthLockJob {

    private static final Logger LOG = LoggerFactory.getLogger(MonthLockJob.class);
    static final String SERVICE_USER = "bpa-service";

    private final MonthLockService locks;
    private final Clock clock;
    private final ZoneId zone;

    public MonthLockJob(MonthLockService locks, Clock clock, ZoneId businessZone) {
        this.locks = locks;
        this.clock = clock;
        this.zone = businessZone;
    }

    @Scheduled(cron = "0 10 0 * * *", zone = "Asia/Riyadh")
    public void run() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        locks.lockDueMonth(today, SERVICE_USER).ifPresent(month -> LOG.info("Month {} locked on {}: man-hour snapshot frozen", month, today));
    }
}
