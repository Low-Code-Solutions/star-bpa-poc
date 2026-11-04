package com.lcs.star.bpa.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one injectable clock and the business zone (09 S9, ADR 0013). Instants are UTC; business dates are read in the zone
 * from config {@code app.business-zone} (Asia/Riyadh). Tests replace the clock to set "today" ("Given today is … (Asia/Riyadh)").
 */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ZoneId businessZone(@Value("${app.business-zone:Asia/Riyadh}") String zone) {
        return ZoneId.of(zone);
    }
}
