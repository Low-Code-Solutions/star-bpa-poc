package com.lcs.star.bpa;

import static com.lcs.star.bpa.CamundaSteps.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;

import com.fasterxml.jackson.databind.JsonNode;
import com.lcs.star.bpa.worker.SendHoursReminderWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * BPA-74 (E2-24): {@code send-hours-reminder}, the job behind the three non-interrupting 24 h timers on the approval tasks. One test per Gherkin scenario, named
 * as in the plan; the extras are ours (plan BPA-74, Questions 1 to 4). Times are Asia/Riyadh in the Gherkin and UTC here (09:00 in Riyadh is 06:00Z). The
 * Background is built with SQL on top of {@link BookHoursFixture}: WP-000457 and one submission with the times the scenario states. The worker's
 * {@code handle} is called with a mocked job (headers {@code step} and {@code recipientGroup}, the process instance and a fresh element instance per timer
 * firing) and the injected clock is set to "when it is ...": nothing waits for real. The recipients are the real members of the Camunda group, and the email
 * goes to Mailpit; the in-system notice is read back through {@code GET /api/v1/me/notifications} as the user.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(AssignmentFileApiTest.TestClockConfig.class)
class HoursReminderApiTest {

    static final long INSTANCE = 9000000741L;
    static final String SUBJECT = "Hours for WP-000457 are waiting for your decision";
    static final String TIMEKEEPER = "timekeeper@example.com";
    static final String PRODUCTION = "production-supervisor@example.com";
    static final String PPC = "ppc-team@example.com";

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
        registry.add("spring.mail.port", () -> Mailpit.smtpPort());
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AssignmentFileApiTest.TestClock clock;

    @Autowired
    SendHoursReminderWorker worker;

    private AssignmentFlow flow;
    private BookHoursFixture.Ids ids;
    private JobClient client;

    @BeforeAll
    static void startMailpit() {
        Mailpit.start();
        IntegrationApp.background();
    }

    @BeforeEach
    void background() {
        jdbc.update("DELETE FROM bpa.notification"); // BookHoursFixture.reset() does not clear it, and the foreign key would stop the delete of the Work Packs
        BookHoursFixture fixture = new BookHoursFixture(jdbc);
        ids = fixture.reset();
        new RevisionFlow(port, jdbc).seedAppUsers();
        flow = new AssignmentFlow(port, jdbc);
        client = mock(JobClient.class, RETURNS_DEEP_STUBS);
    }

    // --- the scenarios --------------------------------------------------------------------------------------------------------

    @Test
    void BPA74_reminderAfter48HoursAtTimekeeper() {
        submission(1, "2026-11-17T06:00:00Z", "Timekeeper"); // sent 17 Nov 09:00, the Timekeeper has not decided
        clock.set(Instant.parse("2026-11-19T06:00:00Z")); // 19 Nov 09:00
        int before = mails(TIMEKEEPER);

        fire("Timekeeper", "timekeeper", 5001L);

        assertThat(reminders("timekeeper")).extracting(r -> r.path("message").asText())
                .containsExactly("Hours for WP-000457 are waiting for your decision since 17 Nov 2026");
        assertThat(mails(TIMEKEEPER) - before).as("one email").isEqualTo(1);
        assertThat(Mailpit.withSubject(SUBJECT)).anySatisfy(m -> {
            assertThat(m.to()).isEqualTo(TIMEKEEPER);
            assertThat(m.body()).contains("Hours for WP-000457 are waiting for your decision since 17 Nov 2026");
        });
        completed();
    }

    @Test
    void theDateInTheNoticeIsTheDateOfRiyadh() {
        // BPA-74 review: sent 17 Nov 22:00Z is 18 Nov 01:00 in Asia/Riyadh (ADR 0013), so the notice says 18 Nov, not 17 Nov
        submission(1, "2026-11-17T22:00:00Z", "Timekeeper");
        clock.set(Instant.parse("2026-11-19T22:00:00Z"));

        fire("Timekeeper", "timekeeper", 5002L);

        assertThat(reminders("timekeeper")).extracting(r -> r.path("message").asText())
                .containsExactly("Hours for WP-000457 are waiting for your decision since 18 Nov 2026");
        completed();
    }

    @Test
    void BPA74_noReminderBefore48Hours() {
        submission(1, "2026-11-17T06:00:00Z", "Timekeeper");
        clock.set(Instant.parse("2026-11-19T05:59:00Z")); // 19 Nov 08:59
        int before = mails(TIMEKEEPER);

        fire("Timekeeper", "timekeeper", 5001L);

        assertThat(reminders("timekeeper")).isEmpty();
        assertThat(mails(TIMEKEEPER) - before).isZero();
        completed();
    }

    @Test
    void BPA74_noReminderOnceDecided() {
        UUID submission = submission(1, "2026-11-17T06:00:00Z", "ProductionSupervisor"); // the hours moved on
        decision(submission, "Timekeeper", "Validated", "2026-11-18T07:00:00Z", 6001L); // validated 18 Nov 10:00
        clock.set(Instant.parse("2026-11-19T06:00:00Z")); // 19 Nov 09:00
        int before = mails(TIMEKEEPER);

        fire("Timekeeper", "timekeeper", 5001L);

        assertThat(reminders("timekeeper")).isEmpty();
        assertThat(mails(TIMEKEEPER) - before).isZero();
        completed();
    }

    @Test
    void BPA74_reminderFollowsHoursToNextStep() {
        UUID submission = submission(1, "2026-11-17T06:00:00Z", "ProductionSupervisor");
        decision(submission, "Timekeeper", "Validated", "2026-11-18T07:00:00Z", 6001L); // reached the Production Supervisor 18 Nov 10:00
        clock.set(Instant.parse("2026-11-20T07:00:00Z")); // 20 Nov 10:00, no decision
        int before = mails(PRODUCTION);

        fire("ProductionSupervisor", "production-supervisor", 5001L);

        assertThat(reminders("production-supervisor")).extracting(r -> r.path("message").asText())
                .containsExactly("Hours for WP-000457 are waiting for your decision since 18 Nov 2026");
        assertThat(mails(PRODUCTION) - before).isEqualTo(1);
        assertThat(reminders("timekeeper")).as("not the Timekeeper any more").isEmpty();
        completed();
    }

    @Test
    void BPA74_reminderRepeatsDaily() {
        UUID submission = submission(1, "2026-11-16T06:00:00Z", "Ppc");
        decision(submission, "Timekeeper", "Validated", "2026-11-16T07:00:00Z", 6001L);
        decision(submission, "ProductionSupervisor", "Approved", "2026-11-17T06:00:00Z", 6002L); // reached PPC 17 Nov 09:00
        int before = mails(PPC);

        clock.set(Instant.parse("2026-11-19T06:00:00Z")); // 19 Nov 09:00: the first reminder
        fire("Ppc", "ppc-team", 5001L);
        clock.set(Instant.parse("2026-11-20T06:00:00Z")); // 20 Nov 09:00, no decision: the next firing of the timer
        fire("Ppc", "ppc-team", 5002L);

        assertThat(reminders("ppc-team")).as("a second reminder").hasSize(2);
        assertThat(mails(PPC) - before).as("a second email").isEqualTo(2);
    }

    // --- not scenarios (plan BPA-74, Questions 1 to 4) ---------------------------------------------------------------------------

    @Test
    void aRerunOfTheSameFiringSendsNothingNew() {
        submission(1, "2026-11-17T06:00:00Z", "Timekeeper");
        clock.set(Instant.parse("2026-11-19T06:00:00Z"));
        int before = mails(TIMEKEEPER);

        fire("Timekeeper", "timekeeper", 5001L);
        fire("Timekeeper", "timekeeper", 5001L); // the job retried: the same timer firing

        assertThat(reminders("timekeeper")).hasSize(1);
        assertThat(mails(TIMEKEEPER) - before).isEqualTo(1);
    }

    @Test
    void aResentSubmissionCountsFromItsOwnSubmittedAt() {
        // Q2: round 1 was Returned; round 2 was sent 20 Nov 09:00: the 48 hours start again from there
        UUID first = submission(1, "2026-11-17T06:00:00Z", "Timekeeper");
        jdbc.update("UPDATE bpa.hours_submission SET stage = 'Closed', outcome = 'Returned' WHERE id = ?", first);
        submission(2, "2026-11-20T06:00:00Z", "Timekeeper");
        int before = mails(TIMEKEEPER);

        clock.set(Instant.parse("2026-11-21T06:00:00Z")); // 24 h after the re-send: not yet
        fire("Timekeeper", "timekeeper", 5001L);
        assertThat(reminders("timekeeper")).isEmpty();

        clock.set(Instant.parse("2026-11-22T06:00:00Z")); // 48 h after the re-send
        fire("Timekeeper", "timekeeper", 5002L);
        assertThat(reminders("timekeeper")).extracting(r -> r.path("message").asText())
                .containsExactly("Hours for WP-000457 are waiting for your decision since 20 Nov 2026");
        assertThat(mails(TIMEKEEPER) - before).isEqualTo(1);
    }

    @Test
    void aReminderDoesNotSpoilTheDrawingNotices() {
        // BPA-16: /me/notifications still lists drawing notices, with their kind, next to the reminder
        jdbc.update("""
                INSERT INTO bpa.notification (workpack_id, drawing_ref, revision, recipient_username, channel, sent_at, created_by, updated_by)
                VALUES (?, 'DWG-118', 'B', 'timekeeper', 'InSystem', TIMESTAMPTZ '2026-11-18 08:00:00+00', 'test', 'test')""", ids.workpackId());
        submission(1, "2026-11-17T06:00:00Z", "Timekeeper");
        clock.set(Instant.parse("2026-11-19T06:00:00Z"));

        fire("Timekeeper", "timekeeper", 5001L);

        List<JsonNode> all = notices("timekeeper");
        assertThat(all).extracting(n -> n.path("kind").asText()).containsExactlyInAnyOrder("DrawingRevision", "HoursReminder");
        JsonNode drawing = all.stream().filter(n -> "DrawingRevision".equals(n.path("kind").asText())).findFirst().orElseThrow();
        assertThat(drawing.path("drawingRef").asText()).isEqualTo("DWG-118");
        assertThat(drawing.path("revision").asText()).isEqualTo("B");
        assertThat(drawing.path("message").isMissingNode()).isTrue();
    }

    @Test
    void aStepWithNoOpenSubmissionDoesNothing() {
        // the hours were Rejected and the round is Closed: a late timer firing finds no submission at the step
        UUID submission = submission(1, "2026-11-17T06:00:00Z", "Timekeeper");
        jdbc.update("UPDATE bpa.hours_submission SET stage = 'Closed', outcome = 'Returned' WHERE id = ?", submission);
        clock.set(Instant.parse("2026-11-19T06:00:00Z"));

        fire("Timekeeper", "timekeeper", 5001L);

        assertThat(reminders("timekeeper")).isEmpty();
        completed();
    }

    // --- helpers ------------------------------------------------------------------------------------------------------------------

    private UUID submission(int round, String submittedAt, String stage) {
        return jdbc.queryForObject("""
                INSERT INTO bpa.hours_submission (workpack_id, process_instance_key, round_no, submitted_by, submitted_at, stage, created_by, updated_by)
                VALUES (?, ?, ?, 'supervisor', ?::timestamptz, ?, 'test', 'test') RETURNING id""", UUID.class, ids.workpackId(), INSTANCE, round, submittedAt, stage);
    }

    private void decision(UUID submission, String step, String decision, String decidedAt, long taskKey) {
        jdbc.update("""
                INSERT INTO bpa.hours_decision (hours_submission_id, step, decision, task_key, decided_by, decided_at, created_by, updated_by)
                VALUES (?, ?, ?, ?, 'test', ?::timestamptz, 'test', 'test')""", submission, step, decision, taskKey, decidedAt);
    }

    /** One firing of the reminder timer: the job of ServiceTask_SendHoursReminder<step>, in the hours' process instance, with its own element instance. */
    private void fire(String step, String recipientGroup, long elementInstance) {
        ActivatedJob job = mock(ActivatedJob.class);
        when(job.getKey()).thenReturn(4_000_000_074L);
        when(job.getProcessInstanceKey()).thenReturn(INSTANCE);
        when(job.getElementInstanceKey()).thenReturn(elementInstance);
        when(job.getCustomHeaders()).thenReturn(Map.of("step", step, "recipientGroup", recipientGroup));
        when(job.getVariablesAsMap()).thenReturn(Map.of("workpackNumber", "WP-000457"));
        worker.handle(client, job);
    }

    private void completed() {
        verify(client).newCompleteCommand(4_000_000_074L);
        verify(client, never()).newFailCommand(anyLong());
    }

    /** The number of reminder emails the address has received so far (Mailpit is shared by the tests of this class). */
    private int mails(String address) {
        return (int) Mailpit.withSubject(SUBJECT).stream().filter(m -> address.equals(m.to())).count();
    }

    private List<JsonNode> notices(String persona) {
        List<JsonNode> all = new ArrayList<>();
        json(flow.loggedInAs(persona).get("/api/v1/me/notifications").body()).forEach(all::add);
        return all;
    }

    private List<JsonNode> reminders(String persona) {
        return notices(persona).stream().filter(n -> "HoursReminder".equals(n.path("kind").asText())).toList();
    }
}
