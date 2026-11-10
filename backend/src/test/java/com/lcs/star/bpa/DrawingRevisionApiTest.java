package com.lcs.star.bpa;

import static com.lcs.star.bpa.CamundaSteps.assertStaysEmpty;
import static com.lcs.star.bpa.CamundaSteps.json;
import static com.lcs.star.bpa.CamundaSteps.poll;
import static com.lcs.star.bpa.RevisionFlow.END_NONE;
import static com.lcs.star.bpa.RevisionFlow.END_NOTIFIED;
import static com.lcs.star.bpa.RevisionFlow.at;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import com.fasterxml.jackson.databind.JsonNode;
import com.lcs.star.bpa.AssignmentFlow.Work;
import com.lcs.star.bpa.Mailpit.Mail;
import com.lcs.star.bpa.adapter.CamundaGroupClient;
import com.lcs.star.bpa.adapter.CamundaProcessClient;
import com.lcs.star.bpa.adapter.CamundaTaskClient.EngineUnavailableException;
import com.lcs.star.bpa.api.TestBrowser;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BPA-16: a drawing revision reaches the Workpack owners and the Project Engineer (POST /api/v1/drawing-revisions, message DrawingRevised,
 * Process_PPC_DrawingRevisionNotice). One test per Gherkin scenario, named as in the Jira DoD (the outlines are one test each). Real
 * everything: the engine, the job workers, the database, and Mailpit as the mock SMTP server (09 S8). The two recipients are the seven-persona
 * users workpack-owner and project-engineer, who are in the groups of the same names (PO decision 2 Nov 2026).
 *
 * <p>Test data. A Work Pack is taken to Released the real way (AssignmentFlow.released(): drawing DWG-118); In Progress and Completed are set by
 * hand (Epic 2 sets them). Every test posts its own revision label, and the revisions of DWG-118 are always IFC, so the release check of a later
 * Work Pack stays Ready. The Background's "latest revision B" is inserted by hand (the copy is otherwise changed only by events); DWG-130 is put
 * back to A and B before each test. Events are registered one hour apart, so each is the latest when it is asserted.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(AssignmentFileApiTest.TestClockConfig.class)
@ExtendWith(OutputCaptureExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DrawingRevisionApiTest {

    static final String DWG = "DWG-118";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void camundaAndMail(DynamicPropertyRegistry registry) {
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

    /** Real except where a scenario says so: removing real users from the groups would break the other classes that share the one Camunda. */
    @MockitoSpyBean
    CamundaGroupClient groups;

    @MockitoSpyBean
    CamundaProcessClient camunda;

    private AssignmentFlow flow;
    private RevisionFlow revisions;

    @BeforeAll
    static void start() {
        IntegrationApp.background();
        Mailpit.start();
    }

    @BeforeEach
    void today() {
        clock.set(AssignmentFileApiTest.TODAY);
        flow = new AssignmentFlow(port, jdbc);
        revisions = new RevisionFlow(port, jdbc);
        // DWG-130 as the Background of the scenarios that use it: revisions A (Superseded) and B (IFC, the latest) only
        jdbc.update("DELETE FROM bpa.drawing_revision WHERE drawing_ref = 'DWG-130' AND revision NOT IN ('A', 'B')");
    }

    private static String subject(String drawing, String revision) {
        return "Drawing " + drawing + " revised to " + revision;
    }

    private List<Mail> awaitMails(String drawing, String revision, int count) {
        return poll(() -> Mailpit.withSubject(subject(drawing, revision)), mails -> mails.size() >= count,
                count + " emails \"" + subject(drawing, revision) + "\"");
    }

    // --- the scenarios ----------------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    void BPA16_revisionNotifiesOwnersAndProjectEngineer() {
        Work wp = flow.released();
        revisions.seedRevision(DWG, "B", "IFC", Instant.parse("2026-10-01T08:00:00Z"));

        HttpResponse<String> response = revisions.receive(DWG, "C", "IFC", RevisionFlow.FIRST, "B");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(revisions.latest(DWG)).isEqualTo("C");
        assertThat(revisions.status(DWG, "B")).as("as reported by the event (D45, D53)").isEqualTo("Superseded");
        List<Mail> mails = awaitMails(DWG, "C", 2);
        assertThat(mails).extracting(Mail::to).containsExactlyInAnyOrder("workpack-owner@example.com", "project-engineer@example.com");
        assertThat(mails).allSatisfy(mail -> assertThat(mail.body()).contains("Work Pack(s): " + wp.number(), "Drawing: DWG-118",
                "Previous revision: B", "New revision: C", "Status: IFC", "Registered at: 28 Oct 2026 09:30 (Asia/Riyadh)"));
        revisions.awaitEnd(revisions.awaitInstance(DWG, "C"), END_NOTIFIED);
        for (String user : List.of("workpack-owner", "project-engineer")) {
            assertThat(revisions.notices(user, "")).as("the notice of " + user).anySatisfy(notice -> {
                assertThat(notice.path("workpackNumber").asText()).isEqualTo(wp.number());
                assertThat(notice.path("drawingRef").asText()).isEqualTo(DWG);
                assertThat(notice.path("revision").asText()).isEqualTo("C");
                assertThat(notice.path("sentAt").asText()).isNotBlank();
            });
        }
        assertThat(revisions.rows(DWG, "C", "InSystem")).isEqualTo(2);
        assertThat(revisions.rows(DWG, "C", "Email")).isEqualTo(2);
    }

    @Test
    void BPA16_eventWithoutSupersededRevisionKeepsStatus() {
        HttpResponse<String> response = revisions.receive("DWG-130", "C", "IFR", at(2), null);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(revisions.latest("DWG-130")).isEqualTo("C");
        assertThat(revisions.status("DWG-130", "B")).as("B is still IFC").isEqualTo("IFC");
    }

    @Test
    void BPA16_supersededRevisionNotInCopyIsLogged(CapturedOutput output) {
        List<String> superseded = revisions.supersededRevisions(DWG);

        HttpResponse<String> response = revisions.receive(DWG, "C3", "IFC", at(3), "Z9");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(revisions.latest(DWG)).isEqualTo("C3");
        assertThat(revisions.supersededRevisions(DWG)).as("no revision of DWG-118 becomes Superseded").isEqualTo(superseded);
        assertThat(output.getAll()).contains("Superseded revision Z9 not found for DWG-118");
    }

    @Test
    void BPA16_sameRevisionTwiceIsIgnored() {
        flow.released();
        assertThat(revisions.receive(DWG, "C4", "IFC", at(4), null).statusCode()).isEqualTo(201);
        awaitMails(DWG, "C4", 2);
        revisions.awaitEnd(revisions.awaitInstance(DWG, "C4"), END_NOTIFIED);
        int notices = revisions.rows(DWG, "C4", "InSystem") + revisions.rows(DWG, "C4", "Email");
        assertThat(notices).isPositive();

        HttpResponse<String> again = revisions.receive(DWG, "C4", "IFC", at(4), null);

        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(json(again.body()).path("message").asText()).isEqualTo("Revision already received");
        assertStaysEmpty(() -> revisions.instancesFor(DWG, "C4").stream().skip(1).toList(), "second notice instance");
        assertThat(Mailpit.withSubject(subject(DWG, "C4"))).as("no new email").hasSize(2);
        assertThat(revisions.rows(DWG, "C4", "InSystem") + revisions.rows(DWG, "C4", "Email")).as("no new notification").isEqualTo(notices);
    }

    @Test
    void BPA16_nobodyToNotify(CapturedOutput output) {
        flow.released();
        Mockito.doReturn(List.of()).when(groups).usernames(anyString());

        assertThat(revisions.receive(DWG, "C5", "IFC", at(5), null).statusCode()).isEqualTo(201);

        revisions.awaitEnd(revisions.awaitInstance(DWG, "C5"), END_NONE);
        assertThat(output.getAll()).contains("No recipients for DWG-118 C5");
        assertStaysEmpty(() -> Mailpit.withSubject(subject(DWG, "C5")), "email");
        assertThat(revisions.rows(DWG, "C5", "InSystem")).isZero();
    }

    @Test
    void BPA16_unlinkedDrawingNotifiesNobody() {
        assertThat(revisions.receive("DWG-121", "B", "IFC", at(6), null).statusCode()).isEqualTo(201);

        revisions.awaitEnd(revisions.awaitInstance("DWG-121", "B"), END_NONE);
        assertStaysEmpty(() -> Mailpit.withSubject(subject("DWG-121", "B")), "email");
        assertThat(revisions.rows("DWG-121", "B", "InSystem") + revisions.rows("DWG-121", "B", "Email")).isZero();
    }

    @Test
    void BPA16_onlyReleasedOrInProgressWorkPacksAreNotified() {
        Work released = flow.released();
        Work inProgress = flow.released();
        jdbc.update("UPDATE bpa.workpack SET status = 'In Progress' WHERE id = ?", inProgress.id());
        Work draft = flow.draft();
        Work completed = flow.draft();
        jdbc.update("UPDATE bpa.workpack SET status = 'Completed' WHERE id = ?", completed.id());

        assertThat(revisions.receive(DWG, "C7", "IFC", at(7), null).statusCode()).isEqualTo(201);

        List<Mail> mails = awaitMails(DWG, "C7", 2);
        revisions.awaitEnd(revisions.awaitInstance(DWG, "C7"), END_NOTIFIED);
        assertThat(mails).allSatisfy(mail -> assertThat(mail.body()).contains(released.number(), inProgress.number())
                .doesNotContain(draft.number(), completed.number()));
        assertThat(noticesFor(released)).as("Released: sent").isEqualTo(4);
        assertThat(noticesFor(inProgress)).as("In Progress: sent").isEqualTo(4);
        assertThat(noticesFor(draft)).as("Draft: not sent").isZero();
        assertThat(noticesFor(completed)).as("Completed: not sent").isZero();
    }

    private int noticesFor(Work wp) {
        return jdbc.queryForObject("SELECT count(*) FROM bpa.notification WHERE workpack_id = ? AND revision = 'C7'", Integer.class, wp.id());
    }

    @Test
    void BPA16_revisionLabelIsTakenAsGiven() {
        HttpResponse<String> response = revisions.receive("DWG-130", "X1", "IFR", at(8), null);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(revisions.latest("DWG-130")).isEqualTo("X1");
    }

    @Test
    void BPA16_unknownDrawingIsRejected() {
        HttpResponse<String> response = revisions.receive("DWG-999", "A", "IFC", at(9), null);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json(response.body()).path("detail").asText()).isEqualTo("Drawing not found");
        assertThat(revisions.revisions("DWG-999", "A")).isZero();
        assertStaysEmpty(() -> revisions.instancesFor("DWG-999", "A"), "notice instance");
        assertThat(Mailpit.withSubject(subject("DWG-999", "A"))).isEmpty();
    }

    @Test
    void BPA16_unknownStatusIsRejected() {
        assertThat(revisions.receive("DWG-130", "D", "IFA", at(10), null).statusCode()).isEqualTo(201);
        assertThat(revisions.receive("DWG-130", "E", "IFI", at(11), null).statusCode()).isEqualTo(201);
        assertThat(revisions.receive("DWG-130", "F", "As-Built", at(12), null).statusCode()).isEqualTo(201);

        HttpResponse<String> response = revisions.receive("DWG-130", "G", "DRAFT", at(13), null);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response.body()).path("detail").asText()).isEqualTo("Unknown drawing status");
        assertThat(revisions.revisions("DWG-130", "G")).as("nothing is recorded").isZero();
    }

    @Test
    void BPA16_userSeesOnlyOwnNotices() {
        Work wp = flow.released();
        assertThat(revisions.receive(DWG, "C11", "IFC", at(11), null).statusCode()).isEqualTo(201);
        awaitMails(DWG, "C11", 2);
        revisions.awaitEnd(revisions.awaitInstance(DWG, "C11"), END_NOTIFIED);
        // a notice that belongs to project-engineer alone
        jdbc.update("""
                INSERT INTO bpa.notification (workpack_id, drawing_ref, revision, recipient_username, channel, sent_at, created_by, updated_by)
                VALUES (?, 'DWG-118', 'ONLY-PE', 'project-engineer', 'InSystem', now(), 'test', 'test')
                """, wp.id());

        List<JsonNode> own = revisions.notices("workpack-owner", "");

        assertThat(own).anySatisfy(n -> assertThat(n.path("revision").asText()).isEqualTo("C11"));
        assertThat(own).noneSatisfy(n -> assertThat(n.path("revision").asText()).isEqualTo("ONLY-PE"));
        assertThat(own).hasSize(jdbc.queryForObject(
                "SELECT count(*) FROM bpa.notification WHERE recipient_username = 'workpack-owner' AND channel = 'InSystem'", Integer.class));
        for (String query : List.of("?recipientUsername=project-engineer", "?username=project-engineer", "?recipientId=project-engineer",
                "?user=project-engineer")) {
            assertThat(revisions.notices("workpack-owner", query)).as("no parameter selects another user: " + query).isEqualTo(own);
        }
        assertThat(revisions.notices("project-engineer", "")).anySatisfy(n -> assertThat(n.path("revision").asText()).isEqualTo("ONLY-PE"));
    }

    // --- the contract and the security rule, not Gherkin scenarios ------------------------------------------------------

    @Test
    void BPA16_postNeedsNoSessionAndNoCsrfTokenButOtherPostsStillDo() {
        assertThat(revisions.receive("DWG-130", "H", "IFC", at(14), null).statusCode()).as("no session, no token (S28)").isEqualTo(201);
        TestBrowser analyst = flow.loggedInAs("time-method-analyst");
        assertThat(analyst.postWithoutCsrfToken("/api/v1/workpacks", "").statusCode()).as("another POST with a session still needs the token (S26)")
                .isEqualTo(403);
    }

    @Test
    void BPA16_missingFieldIsRejected() {
        HttpResponse<String> response = new TestBrowser(port).postWithoutCsrfToken("/api/v1/drawing-revisions", "{\"drawingRef\":\"DWG-118\"}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response.body()).path("detail").asText()).isEqualTo("drawingRef, revision, status and registeredAt are required");
    }

    @Test
    void BPA16_engineDownAnswers503AndRecordsNothing() {
        Mockito.doThrow(new EngineUnavailableException("down")).when(camunda).correlate(eq("DrawingRevised"), anyString(), anyMap());

        HttpResponse<String> response = revisions.receive("DWG-130", "J", "IFC", at(15), null);

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(revisions.revisions("DWG-130", "J")).as("recorded only together with the message (09 G6)").isZero();
    }

    @Test
    void BPA16_noticesNeedALogin() {
        assertThat(new TestBrowser(port).get("/api/v1/me/notifications").statusCode()).isEqualTo(401);
    }
}
