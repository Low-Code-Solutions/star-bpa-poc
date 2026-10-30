package com.lcs.star.bpa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Predicate;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/** Test helper: raw Camunda 8.10 REST calls as a given user, as in the BPA-25 kit (run-bpa25.sh). */
final class CamundaRest {

    record Reply(int status, JsonNode body) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient rest;

    CamundaRest(String restUrl, String username, String password) {
        RestClient.Builder builder = RestClient.builder().baseUrl(restUrl);
        if (username != null) {
            builder.defaultHeaders(h -> h.setBasicAuth(username, password, StandardCharsets.UTF_8));
        }
        this.rest = builder.build();
    }

    Reply get(String path) {
        return rest.get().uri(path).exchange((req, res) -> reply(res.getStatusCode().value(), res.getBody().readAllBytes()));
    }

    Reply post(String path, Object body) {
        return rest.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body)
                .exchange((req, res) -> reply(res.getStatusCode().value(), res.getBody().readAllBytes()));
    }

    Reply deploy(Resource... resources) {
        var parts = new LinkedMultiValueMap<String, Object>();
        for (Resource resource : resources) {
            parts.add("resources", resource);
        }
        return rest.post().uri("/v2/deployments").contentType(MediaType.MULTIPART_FORM_DATA).body(parts)
                .exchange((req, res) -> reply(res.getStatusCode().value(), res.getBody().readAllBytes()));
    }

    /** Search endpoints read secondary storage and are eventually consistent (BPA-25), so assertions poll. */
    Reply pollPost(String path, Object body, Predicate<Reply> done) {
        long until = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        Reply last;
        do {
            last = post(path, body);
            if (done.test(last)) {
                return last;
            }
            sleep();
        } while (System.nanoTime() < until);
        return last;
    }

    Reply pollGet(String path, Predicate<Reply> done) {
        long until = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        Reply last;
        do {
            last = get(path);
            if (done.test(last)) {
                return last;
            }
            sleep();
        } while (System.nanoTime() < until);
        return last;
    }

    private static Reply reply(int status, byte[] body) throws java.io.IOException {
        return new Reply(status, body.length == 0 ? JSON.nullNode() : JSON.readTree(body));
    }

    private static void sleep() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
