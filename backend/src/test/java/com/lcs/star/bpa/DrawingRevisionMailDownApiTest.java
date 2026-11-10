package com.lcs.star.bpa;

import static com.lcs.star.bpa.CamundaSteps.elements;
import static com.lcs.star.bpa.CamundaSteps.poll;
import static org.assertj.core.api.Assertions.assertThat;

import com.lcs.star.bpa.AssignmentFlow.Work;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BPA-16 scenario "Mail server unavailable", in a class of its own because the whole application points at an SMTP port nothing listens on
 * (Mailpit is "down"). The in-system notices are stored; the email is tried 3 times for each recipient (Camunda's default retries, immediate; 09 S7)
 * and then the job raises an incident. A spy on the mail sender counts the attempts.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(AssignmentFileApiTest.TestClockConfig.class)
class DrawingRevisionMailDownApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void camundaAndDeadMail(DynamicPropertyRegistry registry) throws IOException {
        IntegrationApp.background();
        registry.add("camunda.client.rest-address", IntegrationCamunda::restUrl);
        registry.add("camunda.client.grpc-address", IntegrationCamunda::grpcUrl);
        registry.add("bpa.camunda.rest-url", IntegrationCamunda::restUrl);
        registry.add("camunda.client.auth.password", () -> IntegrationApp.servicePassword);
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        registry.add("spring.mail.host", () -> "localhost");
        registry.add("spring.mail.port", () -> closedPort);
        registry.add("spring.mail.properties.mail.smtp.connectiontimeout", () -> "2000");
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AssignmentFileApiTest.TestClock clock;

    @MockitoSpyBean
    JavaMailSender mailSender;

    private AssignmentFlow flow;
    private RevisionFlow revisions;

    @BeforeAll
    static void start() {
        IntegrationApp.background();
    }

    @BeforeEach
    void today() {
        clock.set(AssignmentFileApiTest.TODAY);
        flow = new AssignmentFlow(port, jdbc);
        revisions = new RevisionFlow(port, jdbc);
        revisions.seedAppUsers();
    }

    private static long incidents(long instance) {
        return elements(IntegrationApp.admin.post("/v2/incidents/search",
                Map.of("filter", Map.of("processInstanceKey", Long.toString(instance), "state", "ACTIVE"))).body().path("items")).size();
    }

    /** The worker's own calls, send(SimpleMailMessage): the spy also records the sender's internal calls to its array and MimeMessage overloads. */
    private int sendAttempts() {
        return (int) Mockito.mockingDetails(mailSender).getInvocations().stream()
                .filter(i -> "send".equals(i.getMethod().getName()) && i.getMethod().getParameterTypes().length == 1
                        && i.getMethod().getParameterTypes()[0] == org.springframework.mail.SimpleMailMessage.class)
                .count();
    }

    @Test
    void BPA16_mailServerDown() {
        Work wp = flow.released();

        HttpResponse<String> response = revisions.receive("DWG-118", "MD1", "IFC", RevisionFlow.at(1), null);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        long instance = revisions.awaitInstance("DWG-118", "MD1");
        poll(() -> incidents(instance), count -> count >= 2, "an incident for each recipient's email job");
        assertThat(revisions.rows("DWG-118", "MD1", "InSystem")).as("the in-system notices of workpack-owner and project-engineer for " + wp.number())
                .isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT recipient_username FROM bpa.notification WHERE revision = 'MD1' AND channel = 'InSystem'", String.class))
                .containsExactlyInAnyOrder("workpack-owner", "project-engineer");
        assertThat(sendAttempts()).as("3 tries for each of the 2 recipients").isEqualTo(6);
        assertThat(revisions.rows("DWG-118", "MD1", "Email")).as("nothing was sent").isZero();
        assertThat(incidents(instance)).isEqualTo(2);
        List<Long> leftover = revisions.instancesFor("DWG-118", "MD1");
        leftover.forEach(i -> IntegrationApp.admin.post("/v2/process-instances/" + i + "/cancellation", Map.of()));
    }
}
