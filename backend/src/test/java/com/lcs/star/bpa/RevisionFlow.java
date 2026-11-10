package com.lcs.star.bpa;

import static com.lcs.star.bpa.CamundaSteps.completed;
import static com.lcs.star.bpa.CamundaSteps.elements;
import static com.lcs.star.bpa.CamundaSteps.json;
import static com.lcs.star.bpa.CamundaSteps.poll;
import static com.lcs.star.bpa.CamundaSteps.toJson;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.lcs.star.bpa.api.TestBrowser;
import com.lcs.star.bpa.config.PersonaFile;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test helper for BPA-16: sends DrawingRevised events the way D365 would (no session, no CSRF token; S28), reads what the app recorded, and
 * finds the Process_PPC_DrawingRevisionNotice instance a revision started (by the variables the message carries). Every test uses its own
 * revision label, so a (drawing, revision) pair identifies one instance and its emails.
 */
final class RevisionFlow {

    static final String END_NOTIFIED = "EndEvent_RecipientsNotified";
    static final String END_NONE = "EndEvent_NoRecipients";
    /** The first event of the class (BPA-16 scenario 1); later events are registered later, so each is the latest when it is asserted. */
    static final Instant FIRST = Instant.parse("2026-10-28T06:30:00Z");

    private final int port;
    private final JdbcTemplate jdbc;

    RevisionFlow(int port, JdbcTemplate jdbc) {
        this.port = port;
        this.jdbc = jdbc;
    }

    /**
     * The seven personas as app_user rows in this class's own database, as the S22 persona sync writes them at start-up of the local profile (the sync in
     * IntegrationApp runs against the shared Camunda's database, not this one). The recipients are found by Camunda username and their email is read here (D63).
     */
    void seedAppUsers() {
        for (PersonaFile.Persona persona : PersonaFile.load(new ClassPathResource("personas.yaml")).users()) {
            jdbc.update("""
                    INSERT INTO bpa.app_user (username, employee_ref, email, created_by, updated_by)
                    VALUES (?, ?, ?, 'test', 'test') ON CONFLICT (username) DO NOTHING
                    """, persona.username(), persona.employeeRef(), persona.email());
        }
    }

    static Instant at(int hoursAfterFirst) {
        return FIRST.plusSeconds(3600L * hoursAfterFirst);
    }

    HttpResponse<String> receive(String drawing, String revision, String status, Instant registeredAt, String superseded) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("drawingRef", drawing);
        event.put("revision", revision);
        event.put("status", status);
        event.put("registeredAt", registeredAt.toString());
        if (superseded != null) {
            event.put("supersededRevision", superseded);
        }
        return new TestBrowser(port).postWithoutCsrfToken("/api/v1/drawing-revisions", toJson(event));
    }

    void seedRevision(String drawing, String revision, String status, Instant registeredAt) {
        jdbc.update("""
                INSERT INTO bpa.drawing_revision (drawing_ref, revision, status, registered_at, created_by, updated_by)
                VALUES (?, ?, ?, ?, 'test', 'test') ON CONFLICT (drawing_ref, revision) DO NOTHING
                """, drawing, revision, status, Timestamp.from(registeredAt));
    }

    /** The latest revision is the one most recently registered (D42). */
    String latest(String drawing) {
        return jdbc.queryForObject("SELECT revision FROM bpa.drawing_revision WHERE drawing_ref = ? ORDER BY registered_at DESC, revision DESC LIMIT 1",
                String.class, drawing);
    }

    String status(String drawing, String revision) {
        return jdbc.queryForObject("SELECT status FROM bpa.drawing_revision WHERE drawing_ref = ? AND revision = ?", String.class, drawing, revision);
    }

    int revisions(String drawing, String revision) {
        return jdbc.queryForObject("SELECT count(*) FROM bpa.drawing_revision WHERE drawing_ref = ? AND revision = ?", Integer.class, drawing, revision);
    }

    List<String> supersededRevisions(String drawing) {
        return jdbc.queryForList("SELECT revision FROM bpa.drawing_revision WHERE drawing_ref = ? AND status = 'Superseded' ORDER BY revision", String.class, drawing);
    }

    int rows(String drawing, String revision, String channel) {
        return jdbc.queryForObject("SELECT count(*) FROM bpa.notification WHERE drawing_ref = ? AND revision = ? AND channel = ?", Integer.class,
                drawing, revision, channel);
    }

    /** The notice process instances this revision started (found by the variables DrawingRevised carries). */
    List<Long> instancesFor(String drawing, String revision) {
        List<Long> found = new ArrayList<>();
        for (JsonNode variable : elements(IntegrationApp.admin.post("/v2/variables/search",
                Map.of("filter", Map.of("name", "revision"), "page", Map.of("limit", 1000))).body().path("items"))) {
            long instance = Long.parseLong(variable.path("processInstanceKey").asText());
            if (revision.equals(unquote(variable.path("value").asText())) && drawing.equals(unquote(CamundaSteps.variable(instance, "drawingRef")))
                    && !found.contains(instance)) {
                found.add(instance);
            }
        }
        return found;
    }

    long awaitInstance(String drawing, String revision) {
        return poll(() -> instancesFor(drawing, revision), list -> !list.isEmpty(), "the notice instance of " + drawing + " " + revision).get(0);
    }

    void awaitEnd(long instance, String endElementId) {
        poll(() -> completed(instance, endElementId, 1), done -> done, "the notice instance " + instance + " at " + endElementId);
    }

    /** GET /api/v1/me/notifications as this user (a query string may be added to try to read another user's notices). */
    List<JsonNode> notices(String user, String query) {
        HttpResponse<String> response = new AssignmentFlow(port, jdbc).loggedInAs(user).get("/api/v1/me/notifications" + query);
        assertThat(response.statusCode()).as("GET /api/v1/me/notifications as " + user + ": " + response.body()).isEqualTo(200);
        return elements(json(response.body()));
    }

    static String unquote(String jsonText) {
        return jsonText == null ? null : jsonText.replace("\"", "");
    }
}
