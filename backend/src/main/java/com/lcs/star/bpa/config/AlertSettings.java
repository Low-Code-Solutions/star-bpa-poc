package com.lcs.star.bpa.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The alert and month-lock settings of Epic 2 (BPA-48; D3, S5), read from the environment at each call, as {@link ProgressSettings}: {@code bpa.alerts.pcon-manager-email}
 * is the address of the PCON Manager, who has no login and no tasks and only receives the variance alerts by email (D3); {@code bpa.hours.month-lock-day} is the day of
 * the month on which the previous month locks, 1 (default) or 16 (S5; the {@code hours_month_lock.lock_day} CHECK). Any other value falls back to 1.
 */
@Component
public class AlertSettings {

    static final String PCON_MANAGER_EMAIL = "bpa.alerts.pcon-manager-email";
    static final String MONTH_LOCK_DAY = "bpa.hours.month-lock-day";

    private final Environment environment;

    public AlertSettings(Environment environment) {
        this.environment = environment;
    }

    /** The PCON Manager's address, or empty when none is configured (no email is then sent to them). */
    public String pconManagerEmail() {
        String value = environment.getProperty(PCON_MANAGER_EMAIL, "").trim();
        return value;
    }

    /** The configured month lock day: 1 or 16. */
    public int monthLockDay() {
        String value = environment.getProperty(MONTH_LOCK_DAY, "1").trim();
        return "16".equals(value) ? 16 : 1;
    }
}
