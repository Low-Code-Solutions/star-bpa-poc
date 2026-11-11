package com.lcs.star.bpa.config;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * The one injectable clock and the business zone (09 S9, ADR 0013). Instants are UTC; business dates are read in the zone
 * from config {@code app.business-zone} (Asia/Riyadh). Tests replace the clock to set "today" ("Given today is … (Asia/Riyadh)"): they add their
 * own @Primary bean and do not touch this one.
 *
 * <p>BPA-81, QA only: with profile {@code qa} and {@code BPA_CLOCK_TODAY=<yyyy-MM-dd>} the clock is an offset of the system clock, so the business
 * date in {@code app.business-zone} is that date while time keeps moving (see {@link QaClock}); a WARN at start-up names the date. Without
 * {@code qa} the variable is ignored with a WARN (and not parsed); unset or empty means unset; a malformed value stops start-up.
 */
@Configuration
public class ClockConfig {

    private static final Logger LOG = LoggerFactory.getLogger(ClockConfig.class);

    @Bean
    Clock clock(Environment environment, ZoneId businessZone, @Value("${" + QaClock.VARIABLE + ":}") String today) {
        String value = today == null ? "" : today.trim();
        if (value.isEmpty()) {
            return Clock.systemUTC();
        }
        if (!environment.acceptsProfiles(Profiles.of("qa"))) {
            LOG.warn("{}={} is set but profile qa is not active; the variable is ignored and the clock is the system clock", QaClock.VARIABLE, value);
            return Clock.systemUTC();
        }
        LocalDate target = QaClock.parse(value);
        Clock system = Clock.systemUTC();
        LOG.warn("QA business date is {} ({}, profile qa): the clock runs {} day(s) from the system clock in {}; time keeps moving; for QA only", target,
                QaClock.VARIABLE, QaClock.days(target, system, businessZone), businessZone);
        return QaClock.offsetTo(target, system, businessZone);
    }

    @Bean
    ZoneId businessZone(@Value("${app.business-zone:Asia/Riyadh}") String zone) {
        return ZoneId.of(zone);
    }
}
