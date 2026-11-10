package com.lcs.star.bpa.adapter;

import com.lcs.star.bpa.adapter.CamundaTaskClient.EngineUnavailableException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Reads the members of a Camunda group as {@code bpa-service} (S25), through {@code POST /v2/groups/{groupId}/users/search} (BPA-16, D43 as simplified
 * by the PO decision of 2 Nov 2026: the recipients of a drawing-revision notice are the users of the groups workpack-owner and project-engineer).
 * Needs GROUP READ on those groups (PersonaSync). Probe, Bpa16GroupReadChecksTest: without the permission Camunda answers 200 with an empty list, so an
 * empty answer cannot tell "no members" from "no permission"; the caller says so in its log.
 */
public class CamundaGroupClient {

    private static final int PAGE = 1000;

    private final RestClient rest;

    public CamundaGroupClient(String restUrl, String username, String password) {
        this.rest = RestClient.builder()
                .baseUrl(restUrl)
                .defaultHeaders(h -> h.setBasicAuth(username, password, StandardCharsets.UTF_8))
                .build();
    }

    /** The usernames of the group's members, in the order Camunda returns them. At most 1000 are read (one page; ample for the POC's seven personas). */
    public List<String> usernames(String groupId) {
        try {
            return rest.post().uri("/v2/groups/{groupId}/users/search", groupId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("page", Map.of("limit", PAGE)))
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new EngineUnavailableException("Camunda group member search for " + groupId + " returned "
                                    + response.getStatusCode().value());
                        }
                        Page page = response.bodyTo(Page.class);
                        return page == null || page.items() == null ? List.<String>of() : page.items().stream().map(Member::username).toList();
                    });
        } catch (ResourceAccessException e) {
            throw new EngineUnavailableException("Camunda cannot be reached: " + e.getMessage());
        }
    }

    private record Member(String username) {
    }

    private record Page(List<Member> items) {
    }
}
