package com.lcs.star.bpa.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcs.star.bpa.adapter.CamundaTaskClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The task API's Camunda client, as {@code bpa-service} (S25) with the same credentials as the job workers, and its rules.
 * A plain REST client rather than the starter's {@code CamundaClient} bean, so the API does not depend on
 * {@code camunda.client.enabled} (BPA-26 plan, Design).
 */
@Configuration
@EnableConfigurationProperties(TaskAllowList.class)
class TaskConfig {

    @Bean
    CamundaTaskClient camundaTaskClient(@Value("${bpa.camunda.rest-url}") String restUrl,
            @Value("${camunda.client.auth.username}") String username, @Value("${camunda.client.auth.password}") String password,
            ObjectMapper json) {
        return new CamundaTaskClient(restUrl, username, password, json);
    }

    @Bean
    TaskService taskService(CamundaTaskClient camunda, TaskAllowList allowList, HoursSegregationCheck segregation) {
        return new TaskService(camunda, allowList, segregation);
    }
}
