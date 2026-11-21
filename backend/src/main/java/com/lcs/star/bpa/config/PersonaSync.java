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
 * {@code app_user} and {@code user_project} (BPA-48: the project scope of BPA-76 to BPA-79; no department or role scope). Access to
 * a task is by Camunda group only. Idempotent: existing groups, users and
 * memberships are kept, missing permissions are added. It only adds: removing a persona or one of its groups from
 * {@code personas.yaml} needs a manual step in Camunda Admin.
 *
 * <p>Runs once the singletons exist and before the Camunda client starts (SmartLifecycle), so {@code bpa-service} exists and holds
 * its permissions before the app deploys the models and opens its job workers as that user.
 */
public class PersonaSync implements SmartInitializingSingleton {

    private static final Logger LOG = LoggerFactory.getLogger(PersonaSync.class);

    static final String SERVICE_USER = "bpa-service";

    /** The eleven S6 groups: the seven of Epic 1, and the four of Epic 2 (E2-D1, BPA-48). */
    static final List<String> GROUPS = List.of("time-method-analyst", "project-engineer", "production-planner", "workpack-owner",
            "ppc-team", "supervisor", "production-team", "timekeeper", "production-supervisor", "cost-controller", "project-manager");

    private static final Set<String> PROCESS_PERMISSIONS = Set.of("READ_USER_TASK", "UPDATE_USER_TASK", "COMPLETE_USER_TASK",
            "UPDATE_PROCESS_INSTANCE", "CREATE_PROCESS_INSTANCE");

    /**
     * {@code bpa-service} permissions (S25): the set BPA-25 recorded (comment 10420; shown to work, not proven minimal), plus
     * RESOURCE CREATE so the app can auto-deploy the models as {@code bpa-service} (ASSUMPTION, BPA-43 plan).
     */
    static final Map<String, Set<String>> SERVICE_PERMISSIONS;

    static {
        var permissions = new LinkedHashMap<String, Set<String>>();
        for (String process : List.of("Process_PPC_WorkPackAssignment", "Process_PPC_WorkerReassignment", "Process_PPC_DrawingRevisionNotice",
                "Process_PPC_TimesheetToProgress" /* BPA-49 (ASSUMPTION): started by the WorkPackAssigned message; proven by BPA49_ tests */,
                "Process_PPC_WeeklyProgressPeriod" /* BPA-58 (ASSUMPTION): its jobs (open-progress-period, generate-fms-hours-file, record-fms-hand-off) are activated by bpa-service; proven by the BPA58_ tests */)) {
            permissions.put("PROCESS_DEFINITION:" + process, PROCESS_PERMISSIONS);
        }
        // BPA-50 (ASSUMPTION, proven by the BPA50_ tests): evaluates Decision_HoursWithinBudget at every hour-line save
        permissions.put("DECISION_DEFINITION:Decision_HoursWithinBudget", Set.of("CREATE_DECISION_INSTANCE"));
        permissions.put("MESSAGE:*", Set.of("CREATE"));
        permissions.put("RESOURCE:*", Set.of("CREATE"));
        // BPA-16 (ASSUMPTION): read the members of the two groups that receive a drawing-revision notice, and only those. Without GROUP READ
        // Camunda answers the member search with an empty list (probe, Bpa16GroupReadChecksTest), which would look like "nobody to notify".
        permissions.put("GROUP:workpack-owner", Set.of("READ"));
        permissions.put("GROUP:project-engineer", Set.of("READ"));
        // BPA-64 (ASSUMPTION): read the members of the group that receives the man-hour variance alert (cost-controller), and only that one more. Same reason as above.
        permissions.put("GROUP:cost-controller", Set.of("READ"));
        // BPA-65 (ASSUMPTION): read the members of the group that receives the cost variance alert (project-manager), and evaluate Decision_CostVariance for the daily close check.
        permissions.put("GROUP:project-manager", Set.of("READ"));
        permissions.put("DECISION_DEFINITION:Decision_CostVariance", Set.of("CREATE_DECISION_INSTANCE"));
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
        transaction.executeWithoutResult(status -> {
            personas.users().forEach(this::syncAppUser);
            personas.users().forEach(this::syncUserProjects);
        });
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

    /**
     * Gives a persona its projects from personas.yaml, but only while the user has no user_project row. Once the PPC Team has changed a
     * user's projects in the portal (BPA-76), a restart must not bring a removed project back, so the sync seeds a user and then leaves it
     * alone. If every project of a user was removed, the next start seeds them again (documented in ADR 0005).
     */
    private void syncUserProjects(PersonaFile.Persona persona) {
        Integer existing = jdbc.queryForObject("SELECT count(*) FROM bpa.user_project WHERE username = ?", Integer.class, persona.username());
        if (existing != null && existing > 0) {
            return;
        }
        for (String projectRef : persona.projects()) {
            jdbc.update("""
                    INSERT INTO bpa.user_project (username, project_ref, created_by, updated_by)
                    VALUES (?, ?, 'persona-sync', 'persona-sync')
                    ON CONFLICT (username, project_ref) DO NOTHING
                    """, persona.username(), projectRef);
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not set (.env): the persona sync cannot create users without it");
        }
        return value;
    }
}
