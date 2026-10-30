package com.lcs.star.bpa.adapter;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

/**
 * Verifies a login against Camunda basic auth and reads the user's groups once (S5, ADR 0005; BPA-25 check 3:
 * {@code GET /v2/authentication/me}). The password is used for this one call only and never kept.
 */
public class CamundaIdentityClient {

    private final RestClient rest;

    public CamundaIdentityClient(String restUrl) {
        this.rest = RestClient.builder().baseUrl(restUrl).build();
    }

    /** The user and their Camunda group IDs, or empty when Camunda refuses the username and password (401). */
    public Optional<CamundaUser> verify(String username, String password) {
        return rest.get()
                .uri("/v2/authentication/me")
                .headers(h -> h.setBasicAuth(username, password, StandardCharsets.UTF_8))
                .exchange((request, response) -> {
                    if (response.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value()) {
                        return Optional.<CamundaUser>empty();
                    }
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new IllegalStateException("Camunda login check returned " + response.getStatusCode());
                    }
                    Me me = response.bodyTo(Me.class);
                    return Optional.of(new CamundaUser(me.username(), me.groups() == null ? List.of() : me.groups()));
                });
    }

    public record CamundaUser(String username, List<String> groups) {
    }

    private record Me(String username, List<String> groups) {
    }
}
