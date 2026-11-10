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

/** BPA-43 Part B: the S22 persona sync against the integration Camunda (ADR 0005, L2, L9; Q4, Q5); BPA-44: seven personas, no scope. */
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
        assertThat(identity.verify("time-method-analyst", DEMO_PASSWORD)).as("the sync still ran to the end").isPresent();
        assertThat(permissionsOf("bpa-service")).containsAllEntriesOf(expectedServicePermissions());
    }

    @Test
    @Order(2)
    void BPA44_personaSyncCreatesSevenPersonasInTheirGroups() {
        sync().sync();

        // PO decision 2 Nov 2026: seven personas, username == Camunda group ID, access by group only (BPA-44).
        List<String> groupIds = List.of("time-method-analyst", "project-engineer", "production-planner", "production-team", "ppc-team",
                "supervisor", "workpack-owner");
        assertThat(personas.users()).extracting(PersonaFile.Persona::username).containsExactlyInAnyOrderElementsOf(groupIds);
        for (PersonaFile.Persona persona : personas.users()) {
            var user = identity.verify(persona.username(), DEMO_PASSWORD);
            assertThat(user).as(persona.username() + " logs in with DEMO_PASSWORD").isPresent();
            assertThat(user.get().groups()).as(persona.username() + " groups").containsExactly(persona.username());
        }

        assertThat(jdbc.queryForList("SELECT username FROM bpa.app_user ORDER BY username", String.class))
                .containsExactlyElementsOf(groupIds.stream().sorted().toList());
        assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = 'bpa' AND table_name LIKE '%scope%'",
                String.class)).as("no scope table").isEmpty();
    }

    @Test
    @Order(3)
    void BPA43_personaSyncIsIdempotent() {
        sync().sync();
        Map<String, Set<String>> permissionsBefore = permissionsOf("bpa-service");
        int authorizationsBefore = admin.authorizations("bpa-service").size();

        sync().sync();
        sync().sync();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.app_user", Integer.class)).isEqualTo(7);
        assertThat(admin.authorizations("bpa-service")).hasSize(authorizationsBefore);
        assertThat(permissionsOf("bpa-service")).isEqualTo(permissionsBefore);
    }

    /** BPA-25 comment 10420 (shown to work, not proven minimal), plus RESOURCE CREATE so the app can auto-deploy the models (ASSUMPTION), plus GROUP READ on the two notified groups (BPA-16, ASSUMPTION). */
    static Map<String, Set<String>> expectedServicePermissions() {
        Set<String> process = Set.of("READ_USER_TASK", "UPDATE_USER_TASK", "COMPLETE_USER_TASK", "UPDATE_PROCESS_INSTANCE", "CREATE_PROCESS_INSTANCE");
        return Map.of(
                "PROCESS_DEFINITION:Process_PPC_WorkPackAssignment", process,
                "PROCESS_DEFINITION:Process_PPC_WorkerReassignment", process,
                "PROCESS_DEFINITION:Process_PPC_DrawingRevisionNotice", process,
                "MESSAGE:*", Set.of("CREATE"),
                "RESOURCE:*", Set.of("CREATE"),
                // BPA-16: reads the members of the two groups that receive a drawing-revision notice (probe: without it the search answers an empty list)
                "GROUP:workpack-owner", Set.of("READ"),
                "GROUP:project-engineer", Set.of("READ"));
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
}
