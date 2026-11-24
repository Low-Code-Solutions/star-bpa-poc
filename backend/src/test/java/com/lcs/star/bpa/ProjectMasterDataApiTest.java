package com.lcs.star.bpa;

import static com.lcs.star.bpa.CamundaSteps.json;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.lcs.star.bpa.api.TestBrowser;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BPA-77 (E1 portal): a portal user sees the master data of their own projects only, through {@code GET /api/v1/me/projects} and
 * {@code GET /api/v1/projects/{projectRef}/master-data}, any logged-in group. One test per Gherkin scenario, named as in the plan (Jira gives no names; docs/04);
 * the extras are ours (plan BPA-77, Questions and Resolutions). The Background is built here: the eleven personas as {@code app_user} rows, the rows of
 * {@code user_project} each test needs, and PRJ-142 (not seeded) with one row of each kind of master data: activity A-4201 with CBS-142-PIP-01, drawing DWG-1420
 * with revision A, reservation TR-1420 and cutting sheet CS-1420. PRJ-139 and PRJ-140 come from the seed (V8 links its drawings to them).
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProjectMasterDataApiTest {

    static final String ACTIVITIES_SOR = "P6, read through the D365 PMS database";
    static final String COST_SOR = "D365 F&O Cost Control Report";
    static final String DRAWINGS_SOR = "D365 F&O Document Control";
    static final String MATERIALS_SOR = "D365 F&O (PPC)";
    static final String CUTTING_SOR = "FMS nesting";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void camunda(DynamicPropertyRegistry registry) {
        IntegrationApp.background();
        registry.add("camunda.client.rest-address", IntegrationCamunda::restUrl);
        registry.add("camunda.client.grpc-address", IntegrationCamunda::grpcUrl);
        registry.add("bpa.camunda.rest-url", IntegrationCamunda::restUrl);
        registry.add("camunda.client.auth.password", () -> IntegrationApp.servicePassword);
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private AssignmentFlow flow;

    @BeforeEach
    void background() {
        flow = new AssignmentFlow(port, jdbc);
        new RevisionFlow(port, jdbc).seedAppUsers(); // the eleven personas, as the S22 sync writes them (the sync of IntegrationApp has its own database)
        jdbc.update("DELETE FROM bpa.user_project");
        // PRJ-142 and its master data: one row of each kind, all of it reaching the project (WBS for the activity, project_ref for the drawing)
        jdbc.update("INSERT INTO bpa.project_job_code (project_ref, job_code_ref, created_by, updated_by) VALUES ('PRJ-142', 'JC-142', 'test', 'test') ON CONFLICT (project_ref) DO NOTHING");
        jdbc.update("INSERT INTO bpa.wbs (wbs_ref, project_ref, created_by, updated_by) VALUES ('14201', 'PRJ-142', 'test', 'test') ON CONFLICT (wbs_ref) DO NOTHING");
        jdbc.update("""
                INSERT INTO bpa.activity (activity_ref, activity_name, wbs_ref, created_by, updated_by)
                VALUES ('A-4201', 'Synthetic piping module M-42', '14201', 'test', 'test') ON CONFLICT (activity_ref) DO NOTHING""");
        jdbc.update("INSERT INTO bpa.cbs_activity (activity_ref, cbs_code_ref, created_by, updated_by) VALUES ('A-4201', 'CBS-142-PIP-01', 'test', 'test') ON CONFLICT (activity_ref) DO NOTHING");
        jdbc.update("""
                INSERT INTO bpa.drawing (drawing_ref, title, project_ref, created_by, updated_by)
                VALUES ('DWG-1420', 'Synthetic drawing 1420', 'PRJ-142', 'test', 'test') ON CONFLICT (drawing_ref) DO NOTHING""");
        jdbc.update("""
                INSERT INTO bpa.drawing_revision (drawing_ref, revision, status, registered_at, created_by, updated_by)
                VALUES ('DWG-1420', 'A', 'IFC', TIMESTAMPTZ '2026-10-20 08:00:00+00', 'test', 'test') ON CONFLICT (drawing_ref, revision) DO NOTHING""");
        jdbc.update("""
                INSERT INTO bpa.material_reservation (traceability_number, drawing_ref, material_ref, created_by, updated_by)
                SELECT 'TR-1420', 'DWG-1420', 'MAT-PIPE-06', 'test', 'test'
                WHERE NOT EXISTS (SELECT 1 FROM bpa.material_reservation WHERE traceability_number = 'TR-1420')""");
        jdbc.update("""
                INSERT INTO bpa.cutting_sheet (cutting_sheet_ref, drawing_ref, created_by, updated_by)
                SELECT 'CS-1420', 'DWG-1420', 'test', 'test'
                WHERE NOT EXISTS (SELECT 1 FROM bpa.cutting_sheet WHERE cutting_sheet_ref = 'CS-1420')""");
    }

    // --- the scenarios ----------------------------------------------------------------------------------------------------

    @Test
    void BPA77_userSeesOnlyAssignedProjects() {
        assign("project-engineer", "PRJ-139");
        TestBrowser engineer = flow.loggedInAs("project-engineer");

        HttpResponse<String> response = engineer.get("/api/v1/me/projects");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(refs(json(response.body()), "projectRef")).as("the project list shows PRJ-139 and does not show PRJ-142").containsExactly("PRJ-139");
    }

    @Test
    void BPA77_userWithSeveralProjectsSwitchesProject() {
        assign("time-method-analyst", "PRJ-139");
        assign("time-method-analyst", "PRJ-142");
        TestBrowser analyst = flow.loggedInAs("time-method-analyst");
        String pipeDescription = jdbc.queryForObject("SELECT description FROM bpa.material WHERE material_ref = 'MAT-PIPE-06'", String.class);

        HttpResponse<String> response = analyst.get("/api/v1/projects/PRJ-142/master-data");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode data = json(response.body());
        assertThat(data.path("projectRef").asText()).isEqualTo("PRJ-142");
        // each tab shows PRJ-142's data, and none of PRJ-139's
        assertThat(refs(data.path("activities").path("rows"), "activityRef")).containsExactly("A-4201");
        assertThat(data.path("activities").path("rows").get(0).path("projectRef").asText()).isEqualTo("PRJ-142");
        assertThat(refs(data.path("costStructure").path("rows"), "activityRef")).containsExactly("A-4201");
        assertThat(data.path("costStructure").path("rows").get(0).path("cbsCodeRef").asText()).isEqualTo("CBS-142-PIP-01");
        JsonNode drawing = data.path("drawings").path("rows").get(0);
        assertThat(refs(data.path("drawings").path("rows"), "drawingRef")).containsExactly("DWG-1420");
        assertThat(drawing.path("latestRevision").asText()).isEqualTo("A");
        assertThat(drawing.path("status").asText()).isEqualTo("IFC");
        JsonNode reservation = data.path("reservedMaterials").path("rows").get(0);
        assertThat(refs(data.path("reservedMaterials").path("rows"), "traceabilityNumber")).containsExactly("TR-1420");
        assertThat(reservation.path("drawingRef").asText()).isEqualTo("DWG-1420");
        assertThat(reservation.path("materialRef").asText()).isEqualTo("MAT-PIPE-06");
        assertThat(reservation.path("description").asText()).isEqualTo(pipeDescription);
        assertThat(refs(data.path("cuttingSheets").path("rows"), "cuttingSheetRef")).containsExactly("CS-1420");
        // each tab names its system of record (docs/03-sor-matrix.md)
        assertThat(data.path("activities").path("systemOfRecord").asText()).isEqualTo(ACTIVITIES_SOR);
        assertThat(data.path("costStructure").path("systemOfRecord").asText()).isEqualTo(COST_SOR);
        assertThat(data.path("drawings").path("systemOfRecord").asText()).isEqualTo(DRAWINGS_SOR);
        assertThat(data.path("reservedMaterials").path("systemOfRecord").asText()).isEqualTo(MATERIALS_SOR);
        assertThat(data.path("cuttingSheets").path("systemOfRecord").asText()).isEqualTo(CUTTING_SOR);
    }

    @Test
    void BPA77_unassignedProjectIsRefused() {
        assign("project-engineer", "PRJ-139");
        TestBrowser engineer = flow.loggedInAs("project-engineer");

        HttpResponse<String> response = engineer.get("/api/v1/projects/PRJ-142/master-data");

        assertRefused(response, "Project PRJ-142 is not assigned to you");
    }

    @Test
    void BPA77_userWithNoProjectsSeesEmptyState() {
        TestBrowser supervisor = flow.loggedInAs("supervisor");

        HttpResponse<String> response = supervisor.get("/api/v1/me/projects");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(json(response.body())).as("an empty list, which the screen shows as the empty state").isEmpty();
    }

    // --- not scenarios (plan BPA-77, Questions and Resolutions) -----------------------------------------------------------------

    /** Resolved Q3: one rule for every persona, ppc-team included. It needs its own user_project row like anyone. */
    @Test
    void ppcTeamSeesOnlyItsOwnProjects() {
        TestBrowser ppc = flow.loggedInAs("ppc-team");

        assertThat(json(ppc.get("/api/v1/me/projects").body())).as("no rows, no projects").isEmpty();
        assertRefused(ppc.get("/api/v1/projects/PRJ-139/master-data"), "Project PRJ-139 is not assigned to you");

        assign("ppc-team", "PRJ-140");
        assertThat(refs(json(ppc.get("/api/v1/me/projects").body()), "projectRef")).containsExactly("PRJ-140");
        assertThat(ppc.get("/api/v1/projects/PRJ-140/master-data").statusCode()).isEqualTo(200);
    }

    /** An unknown project gets the same answer as an unassigned one, so the answer does not say whether a project exists (ASSUMPTION). */
    @Test
    void anUnknownProjectIsRefusedLikeAnUnassignedOne() {
        assign("project-engineer", "PRJ-139");
        TestBrowser engineer = flow.loggedInAs("project-engineer");

        assertRefused(engineer.get("/api/v1/projects/PRJ-999/master-data"), "Project PRJ-999 is not assigned to you");
    }

    /** A drawing with no project (V8, ASSUMPTION) belongs to no project: DWG-150 and DWG-151 are in nobody's tabs. */
    @Test
    void aDrawingWithNoProjectIsInNoProject() {
        for (String project : List.of("PRJ-139", "PRJ-140", "PRJ-142")) {
            assign("time-method-analyst", project);
        }
        TestBrowser analyst = flow.loggedInAs("time-method-analyst");

        for (String project : List.of("PRJ-139", "PRJ-140", "PRJ-142")) {
            HttpResponse<String> response = analyst.get("/api/v1/projects/" + project + "/master-data");
            assertThat(response.statusCode()).as(project + ": " + response.body()).isEqualTo(200); // not vacuous: the drawings are really there
            List<String> drawings = refs(json(response.body()).path("drawings").path("rows"), "drawingRef");
            assertThat(drawings).as("drawings of " + project).isNotEmpty().doesNotContain("DWG-150", "DWG-151");
        }
    }

    /** The seeded mapping of V8 (ASSUMPTION): what each seeded project shows, reservations and cutting sheets following their drawing. */
    @Test
    void theSeededProjectsSplitTheDrawings() {
        assign("project-manager", "PRJ-139");
        assign("project-manager", "PRJ-140");
        TestBrowser manager = flow.loggedInAs("project-manager");

        JsonNode p139 = json(manager.get("/api/v1/projects/PRJ-139/master-data").body());
        assertThat(refs(p139.path("activities").path("rows"), "activityRef")).containsExactly("A-2201", "A-2202", "A-2203", "A-2204", "A-2205", "A-2209", "A-2299", "A-2301");
        assertThat(refs(p139.path("costStructure").path("rows"), "activityRef")).containsExactly("A-2201", "A-2202", "A-2203", "A-2204", "A-2205", "A-2301");
        assertThat(refs(p139.path("drawings").path("rows"), "drawingRef")).containsExactly("DWG-118", "DWG-119", "DWG-121", "DWG-130");
        assertThat(refs(p139.path("reservedMaterials").path("rows"), "traceabilityNumber")).containsExactly("TR-5531", "TR-5532", "TR-5533", "TR-5534");
        assertThat(refs(p139.path("cuttingSheets").path("rows"), "cuttingSheetRef")).containsExactly("CS-3310", "CS-3311");
        JsonNode dwg130 = p139.path("drawings").path("rows").get(3);
        assertThat(dwg130.path("latestRevision").asText()).as("DWG-130 has A (Superseded) and B (IFC): the latest is B").isEqualTo("B");
        assertThat(dwg130.path("status").asText()).isEqualTo("IFC");

        JsonNode p140 = json(manager.get("/api/v1/projects/PRJ-140/master-data").body());
        assertThat(refs(p140.path("activities").path("rows"), "activityRef")).containsExactly("A-2206", "A-2207", "A-2208");
        assertThat(p140.path("costStructure").path("rows")).as("PRJ-140 has no cbs_activity row in the seed").isEmpty();
        assertThat(refs(p140.path("drawings").path("rows"), "drawingRef")).containsExactly("DWG-140", "DWG-141");
        assertThat(refs(p140.path("reservedMaterials").path("rows"), "traceabilityNumber")).containsExactly("TR-5535");
        assertThat(refs(p140.path("cuttingSheets").path("rows"), "cuttingSheetRef")).containsExactly("CS-3312");
    }

    /** A guard for the BPA-78 split: the BPA-6 reference lists are not scoped by this story, so a user with no projects still gets every drawing. */
    @Test
    void theReferenceListsAreUnchanged() {
        TestBrowser supervisor = flow.loggedInAs("supervisor");

        HttpResponse<String> response = supervisor.get("/api/v1/drawings");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(refs(json(response.body()), "drawingRef")).contains("DWG-118", "DWG-119", "DWG-121", "DWG-130", "DWG-140", "DWG-141", "DWG-150", "DWG-151");
    }

    @Test
    void everyPersonaCanReadItsOwnProjects() {
        List<String> personas = jdbc.queryForList("SELECT username FROM bpa.app_user ORDER BY username", String.class);
        assertThat(personas).hasSize(11);
        for (String persona : personas) {
            assign(persona, "PRJ-139");
            HttpResponse<String> response = flow.loggedInAs(persona).get("/api/v1/me/projects");
            assertThat(response.statusCode()).as(persona + ": " + response.body()).isEqualTo(200);
            assertThat(refs(json(response.body()), "projectRef")).as(persona).containsExactly("PRJ-139");
        }
    }

    /** QA review: without a session both new paths are 401, not a 403 and not data (the matchers are authenticated(), not permitAll()). */
    @Test
    void withoutASessionBothPathsAre401() {
        TestBrowser nobody = new TestBrowser(port);

        assertThat(nobody.get("/api/v1/me/projects").statusCode()).isEqualTo(401);
        assertThat(nobody.get("/api/v1/projects/PRJ-139/master-data").statusCode()).isEqualTo(401);
    }

    /** QA review: a project reference in the wrong case is not PRJ-139 (references are exact), so it is refused like an unknown project. */
    @Test
    void aProjectInTheWrongCaseIsRefusedLikeAnUnknownOne() {
        assign("project-engineer", "PRJ-139");
        TestBrowser engineer = flow.loggedInAs("project-engineer");

        assertRefused(engineer.get("/api/v1/projects/prj-139/master-data"), "Project prj-139 is not assigned to you");
    }

    // --- helpers ----------------------------------------------------------------------------------------------------------

    private void assign(String username, String projectRef) {
        jdbc.update("INSERT INTO bpa.user_project (username, project_ref, created_by, updated_by) VALUES (?, ?, 'test', 'test') ON CONFLICT (username, project_ref) DO NOTHING",
                username, projectRef);
    }

    /** The values of one field of every element of a JSON array, in order. */
    private static List<String> refs(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        array.forEach(e -> values.add(e.path(field).asText()));
        return values;
    }

    private static void assertRefused(HttpResponse<String> response, String message) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
        assertThat(json(response.body()).path("detail").asText()).isEqualTo(message);
    }
}
