package com.lcs.star.bpatest;

import com.lcs.star.bpa.worker.EvaluateReleaseInputsWorker;
import com.lcs.star.bpa.worker.GenerateWorkPackPrintWorker;
import com.lcs.star.bpa.worker.HourJournalHeaderWorker;
import com.lcs.star.bpa.worker.RecordSignOffWorker;
import com.lcs.star.bpa.worker.SaveWorkPackScheduleWorker;
import com.lcs.star.bpa.worker.SaveWorkPackWorker;
import io.camunda.client.annotation.Deployment;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Camunda Process Test application: only the job workers and the models, no database. It sits in a package of its own, as the
 * Camunda Process Test documentation asks, and lists the worker packages it needs. Workers that need the database are left out
 * (BPA-6: SaveWorkPackWorker; BPA-7: HourJournalHeaderWorker; BPA-8: EvaluateReleaseInputsWorker; BPA-9: RecordSignOffWorker; BPA-11: SaveWorkPackScheduleWorker; BPA-10: GenerateWorkPackPrintWorker); the tests that use this app complete those jobs themselves.
 */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
@ComponentScan(basePackages = "com.lcs.star.bpa.worker",
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {SaveWorkPackWorker.class, HourJournalHeaderWorker.class, EvaluateReleaseInputsWorker.class, RecordSignOffWorker.class, SaveWorkPackScheduleWorker.class, GenerateWorkPackPrintWorker.class}))
@Deployment(resources = {"classpath*:bpmn/*.bpmn", "classpath*:dmn/*.dmn"})
public class ProcessTestApplication {
}
