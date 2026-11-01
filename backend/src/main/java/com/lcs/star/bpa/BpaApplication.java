package com.lcs.star.bpa;

import io.camunda.client.annotation.Deployment;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Models are read from the top-level models/ folder through the pom's resource directories (ADR 0003, L1), never from a copy.
 */
@SpringBootApplication
@Deployment(resources = {"classpath*:bpmn/*.bpmn", "classpath*:dmn/*.dmn"})
public class BpaApplication {

    public static void main(String[] args) {
        SpringApplication.run(BpaApplication.class, args);
    }
}
