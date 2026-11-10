package com.lcs.star.bpa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * Test helper: the mock SMTP server of 09 S8 as one Mailpit container per JVM (BPA-16). The app sends to its SMTP port; the tests read what
 * arrived through Mailpit's HTTP API. Every test posts its own revision label, so a subject identifies the emails of one scenario.
 */
final class Mailpit {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static GenericContainer<?> container;

    /** One email as the tests see it. */
    record Mail(String to, String subject, String body) {
    }

    private Mailpit() {
    }

    static synchronized void start() {
        if (container == null) {
            // Pinned (not latest): v1.32.0 is the release of 22 Oct 2026, older than two weeks when BPA-16 was built.
            container = new GenericContainer<>("axllent/mailpit:v1.32.0").withExposedPorts(1025, 8025)
                    .waitingFor(Wait.forHttp("/api/v1/info").forPort(8025));
            container.start();
        }
    }

    static String host() {
        start();
        return container.getHost();
    }

    static int smtpPort() {
        start();
        return container.getMappedPort(1025);
    }

    private static JsonNode get(String path) {
        try {
            HttpResponse<String> response = HTTP.send(
                    HttpRequest.newBuilder(URI.create("http://" + host() + ":" + container.getMappedPort(8025) + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return JSON.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** The emails with exactly this subject, one entry per recipient address. */
    static List<Mail> withSubject(String subject) {
        List<Mail> found = new ArrayList<>();
        for (JsonNode message : get("/api/v1/messages?limit=500").path("messages")) {
            if (subject.equals(message.path("Subject").asText())) {
                String body = get("/api/v1/message/" + message.path("ID").asText()).path("Text").asText();
                for (JsonNode to : message.path("To")) {
                    found.add(new Mail(to.path("Address").asText(), subject, body));
                }
            }
        }
        return found;
    }
}
