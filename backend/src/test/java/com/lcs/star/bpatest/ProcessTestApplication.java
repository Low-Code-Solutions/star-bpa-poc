package com.lcs.star.bpatest;

import io.camunda.client.annotation.Deployment;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * Camunda Process Test application: only the job workers and the models, no database. It sits in a package of its own, as the
 * Camunda Process Test documentation asks, and lists the worker packages it needs.
 */
@SpringBootApplication(
        scanBasePackages = "com.lcs.star.bpa.worker",
        exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
@Deployment(resources = {"classpath*:bpmn/*.bpmn", "classpath*:dmn/*.dmn"})
public class ProcessTestApplication {
}
