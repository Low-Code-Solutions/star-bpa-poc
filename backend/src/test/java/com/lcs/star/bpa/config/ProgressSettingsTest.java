package com.lcs.star.bpa.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * BPA-83 (plan, the setting): {@code bpa.progress.allow-reopen} is false by default (C-E3-3, decided 11 Nov 2026) and is read at each call, so a demo can switch it on.
 * ProgressSettings had no unit test before; basis and source are covered through the progress tests, so these cover only what BPA-83 adds.
 */
class ProgressSettingsTest {

    @Test
    void reopenIsOffByDefault() {
        assertThat(new ProgressSettings(new MockEnvironment()).allowReopen()).isFalse();
    }

    @Test
    void reopenIsOnWhenTheSettingIsTrue() {
        assertThat(new ProgressSettings(new MockEnvironment().withProperty("bpa.progress.allow-reopen", "true")).allowReopen()).isTrue();
    }

    @Test
    void anythingButTrueIsOff() {
        assertThat(new ProgressSettings(new MockEnvironment().withProperty("bpa.progress.allow-reopen", "false")).allowReopen()).isFalse();
        assertThat(new ProgressSettings(new MockEnvironment().withProperty("bpa.progress.allow-reopen", "maybe")).allowReopen()).isFalse();
    }

    /** Read at each call (not bound once): a change of the environment takes effect at once. */
    @Test
    void theSettingIsReadAtEachCall() {
        MockEnvironment environment = new MockEnvironment();
        ProgressSettings settings = new ProgressSettings(environment);
        assertThat(settings.allowReopen()).isFalse();

        environment.setProperty("bpa.progress.allow-reopen", "true");

        assertThat(settings.allowReopen()).isTrue();
    }
}
