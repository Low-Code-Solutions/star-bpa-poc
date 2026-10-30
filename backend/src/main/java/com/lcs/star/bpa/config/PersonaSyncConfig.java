package com.lcs.star.bpa.config;

import com.lcs.star.bpa.adapter.CamundaAdminClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the S22 persona sync: profiles {@code local} and {@code showcase} only (09 S22, L9; ADR 0005). */
@Configuration
@Profile({"local", "showcase"})
public class PersonaSyncConfig {

    @Bean
    CamundaAdminClient camundaAdminClient(
            @Value("${bpa.camunda.rest-url}") String restUrl,
            @Value("${bpa.camunda.admin.username}") String username,
            @Value("${bpa.camunda.admin.password}") String password) {
        return new CamundaAdminClient(restUrl, username, password);
    }

    @Bean
    PersonaSync personaSync(CamundaAdminClient camunda, JdbcTemplate jdbc,
            @Value("${bpa.personas.file}") Resource file,
            @Value("${bpa.personas.demo-password}") String demoPassword,
            @Value("${bpa.personas.service-password}") String servicePassword) {
        return new PersonaSync(camunda, jdbc, PersonaFile.load(file), demoPassword, servicePassword);
    }
}
