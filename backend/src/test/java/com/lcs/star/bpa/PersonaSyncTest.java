package com.lcs.star.bpa;

import static org.assertj.core.api.Assertions.assertThat;

import com.lcs.star.bpa.adapter.CamundaAdminClient;
import com.lcs.star.bpa.adapter.CamundaIdentityClient;
import com.lcs.star.bpa.config.PersonaFile;
import com.lcs.star.bpa.config.PersonaSync;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

/** BPA-43 Part B: the S22 persona sync against the integration Camunda (ADR 0005, L2, L9; Q4, Q5). */
@Tag("integration")
@TestMethodOrder(OrderAnnotation.class)
class PersonaSyncTest {

    static final String DEMO_PASSWORD = "demo-pw-for-tests";
    static final String SERVICE_PASSWORD = "service-pw-for-tests";
    /** The password of a bpa-service that existed before the sync (made by hand or by the BPA-25 kit). */
    static final String EXISTING_SERVICE_PASSWORD = "password-set-elsewhere";

    static CamundaAdminClient admin;
    static CamundaIdentityClient identity;
    static JdbcTemplate jdbc;
    static PersonaFile personas;

    @BeforeAll
    static void start() {
        IntegrationCamunda.start();
        admin = new CamundaAdminClient(IntegrationCamunda.restUrl(), IntegrationCamunda.ADMIN, IntegrationCamunda.ADMIN_PASSWORD);
        identity = new CamundaIdentityClient(IntegrationCamunda.restUrl());
        jdbc = IntegrationCamunda.jdbc();
        personas = PersonaFile.load(new ClassPathResource("personas.yaml"));
    }

    static PersonaSync sync() {
        return new PersonaSync(admin, jdbc, personas, DEMO_PASSWORD, SERVICE_PASSWORD);
    }

    @Test
    @Order(1)
    void BPA43_personaSyncAcceptsAnExistingBpaService() {
        // bpa-service made earlier by hand or by the BPA-25 kit, with another password (Q4: left as it is). The integration
        // Camunda is shared by the test classes, so another class may have let the sync create it first.
        if (!admin.userExists("bpa-service")) {
            admin.createUser("bpa-service", EXISTING_SERVICE_PASSWORD, "BPA service (technical)", "bpa-service@example.com");
        }
        String existingPassword = servicePassword();

        sync().sync();

        assertThat(identity.verify("bpa-service", existingPassword)).as("existing password kept").isPresent();
        assertThat(identity.verify("tma-1", DEMO_PASSWORD)).as("the sync still ran to the end").isPresent();
        assertThat(permissionsOf("bpa-service")).containsAllEntriesOf(expectedServicePermissions());
    }

    @Test
    @Order(2)
    void BPA43_personaSyncCreatesUsersGroupsAndScopeRows() {
        sync().sync();

        assertThat(personas.users()).hasSize(9);
        for (PersonaFile.Persona persona : personas.users()) {
            var user = identity.verify(persona.username(), DEMO_PASSWORD);
            assertThat(user).as(persona.username() + " logs in with DEMO_PASSWORD").isPresent();
            assertThat(user.get().groups()).as(persona.username() + " groups").containsExactlyInAnyOrderElementsOf(persona.groups());
        }
        Set<String> groups = personas.users().stream().flatMap(p -> p.groups().stream()).collect(Collectors.toSet());
        assertThat(groups).containsExactlyInAnyOrder("time-method-analyst", "project-engineer", "production-planner",
                "workpack-owner", "ppc-team", "supervisor", "production-team");

        assertThat(jdbc.queryForList("SELECT username FROM bpa.app_user ORDER BY username", String.class))
                .containsExactly("pe-1", "planner-1", "ppc-1", "pt-1", "pt-2", "supervisor-1", "tma-1", "tma-2", "wpo-1");
        assertThat(scopeRows()).containsExactlyInAnyOrder(
                "pe-1|project-engineer||PRJ-139",
                "wpo-1|workpack-owner|Fabrication|",
                "pt-1|production-team|Fabrication|",
                "pt-2|production-team|Painting|");
    }

    @Test
    @Order(3)
    void BPA43_personaSyncIsIdempotent() {
        sync().sync();
        List<String> scopeBefore = scopeRows();
        Map<String, Set<String>> permissionsBefore = permissionsOf("bpa-service");
        int authorizationsBefore = admin.authorizations("bpa-service").size();

        sync().sync();
        sync().sync();

        assertThat(scopeRows()).containsExactlyInAnyOrderElementsOf(scopeBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.app_user", Integer.class)).isEqualTo(9);
        assertThat(admin.authorizations("bpa-service")).hasSize(authorizationsBefore);
        assertThat(permissionsOf("bpa-service")).isEqualTo(permissionsBefore);
    }

    /** BPA-25 comment 10420 (shown to work, not proven minimal), plus RESOURCE CREATE so the app can auto-deploy the models (ASSUMPTION). */
    static Map<String, Set<String>> expectedServicePermissions() {
        Set<String> process = Set.of("READ_USER_TASK", "UPDATE_USER_TASK", "COMPLETE_USER_TASK", "UPDATE_PROCESS_INSTANCE", "CREATE_PROCESS_INSTANCE");
        return Map.of(
                "PROCESS_DEFINITION:Process_PPC_WorkPackAssignment", process,
                "PROCESS_DEFINITION:Process_PPC_WorkerReassignment", process,
                "PROCESS_DEFINITION:Process_PPC_DrawingRevisionNotice", process,
                "MESSAGE:*", Set.of("CREATE"),
                "RESOURCE:*", Set.of("CREATE"));
    }

    /** Whichever password the shared bpa-service has: the sync's, or the one it had before the sync. */
    static String servicePassword() {
        return identity.verify("bpa-service", SERVICE_PASSWORD).isPresent() ? SERVICE_PASSWORD : EXISTING_SERVICE_PASSWORD;
    }

    static Map<String, Set<String>> permissionsOf(String owner) {
        return admin.authorizations(owner).stream().collect(Collectors.toMap(
                a -> a.resourceType() + ":" + a.resourceId(), CamundaAdminClient.Authorization::permissionTypes,
                (a, b) -> { throw new IllegalStateException("two authorizations for one resource: " + a + ", " + b); }));
    }

    private static List<String> scopeRows() {
        return jdbc.queryForList("SELECT concat_ws('|', username, group_id, coalesce(department_ref, ''), coalesce(project_ref, '')) "
                + "FROM bpa.user_role_scope", String.class);
    }
}
