package com.lcs.star.bpa.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one injectable clock (09 S9, ADR 0013). Instants are UTC; business dates are read in {@link #BUSINESS_ZONE}. Tests
 * replace the bean to set "today" ("Given today is … (Asia/Riyadh)").
 */
@Configuration
public class ClockConfig {

    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Riyadh");

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
