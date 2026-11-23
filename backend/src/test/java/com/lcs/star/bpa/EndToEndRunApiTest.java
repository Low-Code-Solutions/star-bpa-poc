package com.lcs.star.bpa;

import static com.lcs.star.bpa.CamundaSteps.json;
import static org.assertj.core.api.Assertions.assertThat;

import com.lcs.star.bpa.DemoRun.Run;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BPA-68 (E2-18): one Work Pack on A-2301 run from creation to locked progress across the three processes, through the task API and as each persona
 * (ADR 0014; plan BPA-68, harness: the integration stack, not Camunda Process Test, because every step reads and writes {@code bpa} rows). One test per Gherkin
 * scenario, named as in the Jira Definition of Done. The Background is the seed on a clean database ({@link DemoRun#reset()}) with the clock at 16 Nov 2026 09:00
 * (Asia/Riyadh); the cut-off at Thursday 19 Nov 23:59 is fired directly. BPA-68 adds no production code, so these tests are expected to pass at their first run.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(AssignmentFileApiTest.TestClockConfig.class)
class EndToEndRunApiTest {

    static final String PCON = "pcon.manager@star.example";
    static final String DAILY_LIMIT = "Worker E1021 would exceed 16 hours on 17 Nov 2026";
    static final String ABOVE_100 = "Completion % must be between 0 and 100";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void camunda(DynamicPropertyRegistry registry) {
        IntegrationApp.background();
        registry.add("camunda.client.rest-address", IntegrationCamunda::restUrl);
        registry.add("camunda.client.grpc-address", IntegrationCamunda::grpcUrl);
        registry.add("bpa.camunda.rest-url", IntegrationCamunda::restUrl);
        registry.add("camunda.client.auth.password", () -> IntegrationApp.servicePassword);
        registry.add("spring.mail.host", Mailpit::host);
        registry.add("spring.mail.port", Mailpit::smtpPort);
        registry.add("bpa.alerts.pcon-manager-email", () -> PCON);
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AssignmentFileApiTest.TestClock clock;

    private DemoRun demo;

    @BeforeAll
    static void noInstanceLeftWaitingByAnotherClass() {
        Mailpit.start();
        IntegrationApp.background();
        DemoRun.cancelWaiting();
    }

    @BeforeEach
    void cleanDatabaseAndMonday() {
        demo = new DemoRun(port, jdbc, clock);
        demo.reset();
    }

    // --- the scenarios ----------------------------------------------------------------------------------------------------

    @Test
    void BPA68_workPackRunsFromCreationToLockedProgress() {
        Run run = demo.epic1();

        demo.bookTheDay(run);
        demo.send(run);
        demo.approveHours(run);
        long weekly = demo.cutOff();
        List<List<String>> fmsRows = demo.sendFmsFile(weekly);
        demo.uploadReport(weekly, run);
        assertThat(demo.approveCompletion(weekly, run, "40").statusCode()).as("PPC approves 40 %").isEqualTo(204);
        demo.closeTheWeek(weekly);

        demo.assertEndState(run, fmsRows, "7.5");
    }

    @Test
    void BPA68_returnedHoursRejoinTheRun() {
        Run run = demo.epic1();
        demo.bookTheDay(run);
        demo.send(run);

        HttpResponse<String> returned = demo.decideHours(run, DemoRun.VALIDATE, "timekeeper", "Returned", "Check E1099");
        assertThat(returned.statusCode()).as("Returned: " + returned.body()).isEqualTo(204);
        com.lcs.star.bpa.CamundaSteps.awaitOpenTask(run.timesheetInstance(), com.lcs.star.bpa.AssignmentFlow.BOOK_HOURS);
        assertThat(demo.lines("Returned")).as("both lines Returned, back at Book hours").isEqualTo(2);

        demo.correctE1099(run);
        demo.send(run);
        demo.approveHours(run);
        long weekly = demo.cutOff();
        List<List<String>> fmsRows = demo.sendFmsFile(weekly);
        demo.uploadReport(weekly, run);
        assertThat(demo.approveCompletion(weekly, run, "40").statusCode()).as("PPC approves 40 %").isEqualTo(204);
        demo.closeTheWeek(weekly);

        demo.assertEndState(run, fmsRows, "7");
    }

    @Test
    void BPA68_rulesAreShownLive() {
        Run run = demo.epic1();
        demo.bookTheDay(run);

        // the 16 h daily limit (BPA-51): a 17 Nov line is dated in the past once today is 17 Nov (plan Q5, ASSUMPTION)
        clock.set(DemoRun.TUESDAY);
        HttpResponse<String> over = demo.book(run, "E1021", LocalDate.of(2026, 11, 17), "Cutting", "17");
        assertThat(over.statusCode()).as(over.body()).isEqualTo(400);
        assertThat(json(over.body()).path("detail").asText()).isEqualTo(DAILY_LIMIT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.digital_timesheet_line WHERE work_date = DATE '2026-11-17'", Integer.class)).as("no line saved").isZero();

        demo.send(run);
        demo.approveHours(run);
        long weekly = demo.cutOff();
        demo.sendFmsFile(weekly);
        demo.uploadReport(weekly, run);

        // 120 % (BPA-60, plan Q1): refused at Approve completion, so BPA-61's send-back text cannot come from the screen
        HttpResponse<String> above = demo.approveCompletion(weekly, run, "120");
        assertThat(above.statusCode()).as(above.body()).isEqualTo(400);
        assertThat(json(above.body()).path("detail").asText()).isEqualTo(ABOVE_100);
        assertThat(CamundaSteps.openTasks(weekly, DemoRun.APPROVE_COMPLETION)).as("the task stays open").hasSize(1);
        // QA review: the row exists (the report read set its reported 40 %) and holds no approved %, so the refused 120 was not stored
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.workpack_progress WHERE reported_pct = 40", Integer.class)).as("the reported % is there").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.workpack_progress WHERE approved_pct IS NOT NULL", Integer.class)).as("nothing approved").isZero();
    }

    /** QA review: leave nothing waiting for the next class (its Work Pack is WP-000001 too, and BoundaryEvent_WorkPackCompleted correlates on the number). */
    @AfterAll
    static void leaveNoInstanceWaiting() {
        DemoRun.cancelWaiting();
    }
}
