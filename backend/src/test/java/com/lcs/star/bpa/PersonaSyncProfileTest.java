package com.lcs.star.bpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.lcs.star.bpa.config.PersonaSync;
import com.lcs.star.bpa.config.PersonaSyncConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

/** BPA-43 Part B: where the S22 sync runs, and what personas.yaml may hold (ADR 0005, L9). No containers. */
class PersonaSyncProfileTest {

    @ParameterizedTest
    @ValueSource(strings = {"", "test", "default"})
    void BPA43_personaSyncDoesNotRunOutsideLocalAndShowcase(String profile) {
        new ApplicationContextRunner()
                .withUserConfiguration(PersonaSyncConfig.class)
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withPropertyValues("bpa.camunda.rest-url=http://localhost:1", "bpa.camunda.admin.username=x", "bpa.camunda.admin.password=x",
                        "bpa.personas.file=classpath:personas.yaml", "bpa.personas.demo-password=x", "bpa.personas.service-password=x")
                .withInitializer(context -> {
                    if (!profile.isEmpty()) {
                        context.getEnvironment().setActiveProfiles(profile);
                    }
                })
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(PersonaSync.class));

        Profile only = PersonaSyncConfig.class.getAnnotation(Profile.class);
        assertThat(only).as("@Profile on PersonaSyncConfig").isNotNull();
        assertThat(only.value()).containsExactlyInAnyOrder("local", "showcase");
    }

    @Test
    void BPA43_personasYamlHoldsNoPasswords() throws IOException {
        String yaml = new ClassPathResource("personas.yaml").getContentAsString(StandardCharsets.UTF_8);
        String data = yaml.lines().filter(line -> !line.stripLeading().startsWith("#")).reduce("", (a, b) -> a + b + "\n");

        assertThat(data).doesNotContainIgnoringCase("password").doesNotContainIgnoringCase("secret");
        assertThat(yaml).doesNotContain("change-me-demo").doesNotContain("change-me-service");
    }
}
