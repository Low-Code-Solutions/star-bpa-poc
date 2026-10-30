package com.lcs.star.bpa.adapter;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

/**
 * Camunda identity administration through the 8.10 REST API, called as the Camunda admin (ADR 0005, BPA-43 Q4). Used only by
 * the S22 persona sync, which runs only with profile {@code local} or {@code showcase}. Request shapes as confirmed by BPA-25.
 */
public class CamundaAdminClient {

    private final RestClient rest;

    public CamundaAdminClient(String restUrl, String adminUsername, String adminPassword) {
        this.rest = RestClient.builder()
                .baseUrl(restUrl)
                .defaultHeaders(h -> h.setBasicAuth(adminUsername, adminPassword, StandardCharsets.UTF_8))
                .build();
    }

    /** Creates the group; false when it already exists (409). */
    public boolean createGroupIfAbsent(String groupId) {
        return createdOrConflict(rest.post().uri("/v2/groups").body(Map.of("groupId", groupId, "name", groupId)), "group " + groupId);
    }

    public boolean userExists(String username) {
        return rest.get().uri("/v2/users/{username}", username).exchange((request, response) -> {
            if (response.getStatusCode().value() == 404) {
                return false;
            }
            requireSuccess(response.getStatusCode(), "user lookup " + username);
            return true;
        });
    }

    public void createUser(String username, String password, String name, String email) {
        rest.post().uri("/v2/users")
                .body(Map.of("username", username, "password", password, "name", name, "email", email))
                .exchange((request, response) -> {
                    requireSuccess(response.getStatusCode(), "create user " + username);
                    return null;
                });
    }

    /** Adds the user to the group; an existing membership (409) is accepted. */
    public void addUserToGroup(String groupId, String username) {
        createdOrConflict(rest.put().uri("/v2/groups/{groupId}/users/{username}", groupId, username), "membership " + username + " in " + groupId);
    }

    /** The authorizations owned by a user. */
    public List<Authorization> authorizations(String ownerId) {
        AuthorizationPage page = rest.post().uri("/v2/authorizations/search")
                .body(Map.of("filter", Map.of("ownerId", ownerId, "ownerType", "USER"), "page", Map.of("limit", 1000)))
                .retrieve()
                .body(AuthorizationPage.class);
        return page == null || page.items() == null ? List.of() : page.items();
    }

    public void createAuthorization(String ownerId, String resourceType, String resourceId, Set<String> permissionTypes) {
        rest.post().uri("/v2/authorizations")
                .body(authorizationBody(ownerId, resourceType, resourceId, permissionTypes))
                .retrieve()
                .toBodilessEntity();
    }

    public void updateAuthorization(String authorizationKey, String ownerId, String resourceType, String resourceId, Set<String> permissionTypes) {
        rest.put().uri("/v2/authorizations/{key}", authorizationKey)
                .body(authorizationBody(ownerId, resourceType, resourceId, permissionTypes))
                .retrieve()
                .toBodilessEntity();
    }

    private static Map<String, Object> authorizationBody(String ownerId, String resourceType, String resourceId, Set<String> permissionTypes) {
        return Map.of("ownerId", ownerId, "ownerType", "USER", "resourceType", resourceType, "resourceId", resourceId,
                "permissionTypes", permissionTypes.stream().sorted().toList());
    }

    private static boolean createdOrConflict(RestClient.RequestBodySpec request, String what) {
        return request.exchange((req, response) -> {
            if (response.getStatusCode().value() == 409) {
                return false;
            }
            requireSuccess(response.getStatusCode(), what);
            return true;
        });
    }

    private static void requireSuccess(HttpStatusCode status, String what) {
        if (!status.is2xxSuccessful()) {
            throw new IllegalStateException("Camunda " + what + " returned " + status);
        }
    }

    public record Authorization(String authorizationKey, String resourceType, String resourceId, Set<String> permissionTypes) {
    }

    private record AuthorizationPage(List<Authorization> items) {
    }
}
