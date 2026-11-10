package com.lcs.star.bpa;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.lcs.star.bpa.CamundaRest.Reply;
import com.lcs.star.bpa.adapter.CamundaAdminClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * BPA-16 probe (data model section 8: "the code is built against the observed response"): what Camunda 8.10.1 answers to
 * {@code POST /v2/groups/{groupId}/users/search} when called as {@code bpa-service}, before and after it is given GROUP READ on that
 * group. The recipients worker needs the usernames of the groups workpack-owner and project-engineer. Not a Gherkin scenario.
 */
@Tag("integration")
class Bpa16GroupReadChecksTest {

    static final String GROUP = "workpack-owner";

    @BeforeAll
    static void start() {
        IntegrationApp.background();
    }

    private static Reply search(CamundaRest as) {
        return as.post("/v2/groups/" + GROUP + "/users/search", Map.of("page", Map.of("limit", 100)));
    }

    private static List<String> usernames(Reply reply) {
        List<String> names = new ArrayList<>();
        for (JsonNode item : reply.body().path("items")) {
            names.add(item.path("username").asText());
        }
        return names;
    }

    /** The search is eventually consistent and filtered by authorization: poll up to 20 s for a non-empty answer. */
    private static Reply waitForMembers(CamundaRest as) {
        Reply last = search(as);
        for (int i = 0; i < 20 && usernames(last).isEmpty(); i++) {
            CamundaSteps.pause();
            CamundaSteps.pause();
            last = search(as);
        }
        return last;
    }

    @Test
    void BPA16_probeGroupMembersReadAsTheServiceUser() {
        var service = new CamundaRest(IntegrationCamunda.restUrl(), "bpa-service", IntegrationApp.servicePassword);

        Reply before = search(service);
        System.out.println("BPA-16 PROBE group users as bpa-service, before GROUP READ: status=" + before.status() + " body=" + before.body());

        var admin = new CamundaAdminClient(IntegrationCamunda.restUrl(), IntegrationCamunda.ADMIN, IntegrationCamunda.ADMIN_PASSWORD);
        boolean already = admin.authorizations("bpa-service").stream()
                .anyMatch(a -> "GROUP".equals(a.resourceType()) && GROUP.equals(a.resourceId()) && a.permissionTypes().contains("READ"));
        if (!already) {
            admin.createAuthorization("bpa-service", "GROUP", GROUP, Set.of("READ"));
        }

        Reply asAdmin = search(IntegrationApp.admin);
        System.out.println("BPA-16 PROBE group users as the admin: status=" + asAdmin.status() + " body=" + asAdmin.body());
        assertThat(usernames(asAdmin)).as("members of " + GROUP + " as admin").contains("workpack-owner");

        Reply after = waitForMembers(service);
        System.out.println("BPA-16 PROBE group users as bpa-service, after GROUP READ: status=" + after.status() + " body=" + after.body());
        if (usernames(after).isEmpty()) {
            admin.createAuthorization("bpa-service", "USER", "*", Set.of("READ"));
            after = waitForMembers(service);
            System.out.println("BPA-16 PROBE group users as bpa-service, after GROUP READ and USER READ *: status=" + after.status() + " body=" + after.body());
        }
        assertThat(usernames(after)).as("members of " + GROUP).contains("workpack-owner");
    }
}
