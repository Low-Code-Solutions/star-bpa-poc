package com.lcs.star.bpa.config;

import com.lcs.star.bpa.adapter.CamundaAdminClient;
import com.lcs.star.bpa.adapter.CamundaAdminClient.Authorization;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * S22 persona sync (ADR 0005, L2, L9; BPA-43 Q4, Q5; BPA-44). The only runtime writer of Camunda users and groups and of
 * {@code app_user}. Access is by Camunda group only: there is no department or project scope. Idempotent: existing groups, users and
 * memberships are kept, missing permissions are added. It only adds: removing a persona or one of its groups from
 * {@code personas.yaml} needs a manual step in Camunda Admin.
 *
 * <p>Runs once the singletons exist and before the Camunda client starts (SmartLifecycle), so {@code bpa-service} exists and holds
 * its permissions before the app deploys the models and opens its job workers as that user.
 */
public class PersonaSync implements SmartInitializingSingleton {

    private static final Logger LOG = LoggerFactory.getLogger(PersonaSync.class);

    static final String SERVICE_USER = "bpa-service";

    /** The seven S6 groups. */
    static final List<String> GROUPS = List.of("time-method-analyst", "project-engineer", "production-planner", "workpack-owner",
            "ppc-team", "supervisor", "production-team");

    private static final Set<String> PROCESS_PERMISSIONS = Set.of("READ_USER_TASK", "UPDATE_USER_TASK", "COMPLETE_USER_TASK",
            "UPDATE_PROCESS_INSTANCE", "CREATE_PROCESS_INSTANCE");

    /**
     * {@code bpa-service} permissions (S25): the set BPA-25 recorded (comment 10420; shown to work, not proven minimal), plus
     * RESOURCE CREATE so the app can auto-deploy the models as {@code bpa-service} (ASSUMPTION, BPA-43 plan).
     */
    static final Map<String, Set<String>> SERVICE_PERMISSIONS;

    static {
        var permissions = new LinkedHashMap<String, Set<String>>();
        for (String process : List.of("Process_PPC_WorkPackAssignment", "Process_PPC_WorkerReassignment", "Process_PPC_DrawingRevisionNotice")) {
            permissions.put("PROCESS_DEFINITION:" + process, PROCESS_PERMISSIONS);
        }
        permissions.put("MESSAGE:*", Set.of("CREATE"));
        permissions.put("RESOURCE:*", Set.of("CREATE"));
        SERVICE_PERMISSIONS = Map.copyOf(permissions);
    }

    private final CamundaAdminClient camunda;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final PersonaFile personas;
    private final String demoPassword;
    private final String servicePassword;

    public PersonaSync(CamundaAdminClient camunda, JdbcTemplate jdbc, PersonaFile personas, String demoPassword, String servicePassword) {
        this.camunda = camunda;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        this.personas = personas;
        this.demoPassword = demoPassword;
        this.servicePassword = servicePassword;
    }

    @Override
    public void afterSingletonsInstantiated() {
        sync();
    }

    public void sync() {
        GROUPS.forEach(camunda::createGroupIfAbsent);
        syncServiceUser();
        for (PersonaFile.Persona persona : personas.users()) {
            syncCamundaUser(persona);
        }
        transaction.executeWithoutResult(status -> personas.users().forEach(this::syncAppUser));
        LOG.info("Persona sync done: {} personas, {} groups, {}", personas.users().size(), GROUPS.size(), SERVICE_USER);
    }

    private void syncServiceUser() {
        if (camunda.userExists(SERVICE_USER)) {
            LOG.info("{} already exists; left as it is (password not changed)", SERVICE_USER);
        } else {
            camunda.createUser(SERVICE_USER, required(servicePassword, "BPA_SERVICE_PASSWORD"), "BPA service (technical)", "bpa-service@example.com");
        }
        List<Authorization> existing = camunda.authorizations(SERVICE_USER);
        SERVICE_PERMISSIONS.forEach((resource, wanted) -> {
            String[] typeAndId = resource.split(":", 2);
            Authorization current = existing.stream()
                    .filter(a -> typeAndId[0].equals(a.resourceType()) && typeAndId[1].equals(a.resourceId()))
                    .findFirst().orElse(null);
            if (current == null) {
                camunda.createAuthorization(SERVICE_USER, typeAndId[0], typeAndId[1], wanted);
            } else if (!current.permissionTypes().containsAll(wanted)) {
                Set<String> union = new HashSet<>(current.permissionTypes());
                union.addAll(wanted);
                camunda.updateAuthorization(current.authorizationKey(), SERVICE_USER, typeAndId[0], typeAndId[1], union);
            }
        });
    }

    private void syncCamundaUser(PersonaFile.Persona persona) {
        if (!camunda.userExists(persona.username())) {
            camunda.createUser(persona.username(), required(demoPassword, "DEMO_PASSWORD"), persona.name(), persona.email());
        }
        persona.groups().forEach(group -> camunda.addUserToGroup(group, persona.username()));
    }

    /** Upserts app_user (never delete-and-insert: docs/data-model.md 5.10). */
    private void syncAppUser(PersonaFile.Persona persona) {
        jdbc.update("""
                INSERT INTO bpa.app_user (username, employee_ref, email, created_by, updated_by)
                VALUES (?, ?, ?, 'persona-sync', 'persona-sync')
                ON CONFLICT (username) DO UPDATE
                    SET employee_ref = EXCLUDED.employee_ref, email = EXCLUDED.email, updated_at = now(), updated_by = 'persona-sync'
                    WHERE (bpa.app_user.employee_ref, bpa.app_user.email) IS DISTINCT FROM (EXCLUDED.employee_ref, EXCLUDED.email)
                """, persona.username(), persona.employeeRef(), persona.email());
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not set (.env): the persona sync cannot create users without it");
        }
        return value;
    }
}
