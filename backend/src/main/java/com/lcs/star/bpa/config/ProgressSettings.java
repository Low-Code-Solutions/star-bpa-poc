package com.lcs.star.bpa.config;

import java.util.Locale;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The progress settings of Epic 2 (BPA-48; C-E2-2, C-E2-3), read from the environment at each call (not bound once), so a test or a restart-free
 * override of {@code bpa.progress.source} takes effect at once. {@code source} is FMS (default, FRD-21 §21.9) or PMS (the PMS weekly summary, FRD-22
 * §22.6.2 s10): the completion report upload accepts only rows of that source (BPA-59). An unknown value falls back to FMS.
 */
@Component
public class ProgressSettings {

    static final String SOURCE = "bpa.progress.source";
    static final String BASIS = "bpa.progress.basis";
    static final String ALLOW_REOPEN = "bpa.progress.allow-reopen";

    private final Environment environment;

    public ProgressSettings(Environment environment) {
        this.environment = environment;
    }

    /** The configured progress basis (BPA-60; C-E2-2): "Physical" (default, FRD-21 §21.6: PPC approves the reported physical %) or "Hours" (FRD-7 §7.7.1: pre-filled from logged hours). */
    public String basis() {
        return "HOURS".equalsIgnoreCase(environment.getProperty(BASIS, "Physical").trim()) ? "Hours" : "Physical";
    }

    /** BPA-83 (C-E3-3, decided 11 Nov 2026): whether a Project Manager may reopen a Locked week. False by default; only the value true switches it on. */
    public boolean allowReopen() {
        return "true".equalsIgnoreCase(environment.getProperty(ALLOW_REOPEN, "false").trim());
    }

    /** The configured progress source, "FMS" or "PMS". */
    public String source() {
        String value = environment.getProperty(SOURCE, "FMS").trim().toUpperCase(Locale.ROOT);
        return "PMS".equals(value) ? "PMS" : "FMS";
    }
}
