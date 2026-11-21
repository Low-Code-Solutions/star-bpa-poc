package com.lcs.star.bpa.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on {@code @Scheduled} for the month lock job (BPA-64; plan Q1 option A). The only scheduled job of the application. */
@Configuration
@EnableScheduling
class SchedulingConfig {
}
