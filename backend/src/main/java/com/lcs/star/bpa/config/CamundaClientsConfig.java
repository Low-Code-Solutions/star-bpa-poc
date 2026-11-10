package com.lcs.star.bpa.config;

import com.lcs.star.bpa.adapter.CamundaGroupClient;
import com.lcs.star.bpa.adapter.CamundaProcessClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The process-start client, as {@code bpa-service} (S25) with the same credentials as the job workers (BPA-6). */
@Configuration
class CamundaClientsConfig {

    @Bean
    CamundaProcessClient camundaProcessClient(@Value("${bpa.camunda.rest-url}") String restUrl,
            @Value("${camunda.client.auth.username}") String username, @Value("${camunda.client.auth.password}") String password) {
        return new CamundaProcessClient(restUrl, username, password);
    }

    /** Reads the members of the groups that receive a drawing-revision notice, as {@code bpa-service} (BPA-16). */
    @Bean
    CamundaGroupClient camundaGroupClient(@Value("${bpa.camunda.rest-url}") String restUrl,
            @Value("${camunda.client.auth.username}") String username, @Value("${camunda.client.auth.password}") String password) {
        return new CamundaGroupClient(restUrl, username, password);
    }
}
