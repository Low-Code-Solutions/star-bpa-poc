package com.lcs.star.bpa.api;

import com.lcs.star.bpa.api.generated.ProjectMasterDataApi;
import com.lcs.star.bpa.api.generated.model.ActivitiesTab;
import com.lcs.star.bpa.api.generated.model.CostStructureTab;
import com.lcs.star.bpa.api.generated.model.CuttingSheetsTab;
import com.lcs.star.bpa.api.generated.model.DrawingsTab;
import com.lcs.star.bpa.api.generated.model.Project;
import com.lcs.star.bpa.api.generated.model.ProjectMasterData;
import com.lcs.star.bpa.api.generated.model.ReservedMaterialsTab;
import com.lcs.star.bpa.persistence.ProjectMasterDataRepository;
import com.lcs.star.bpa.scope.ProjectNotAssignedException;
import com.lcs.star.bpa.scope.ProjectScope;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;

/**
 * Projects and master data (BPA-77): the session user's projects, and the master data of one of them in five tabs, each naming the system of record it comes from
 * (docs/03-sor-matrix.md; seeded copies in the POC). Read only, any logged-in group. The only scope rule is {@link ProjectScope}: a project the user has no
 * {@code user_project} row for, or that does not exist, is a 403 "Project PRJ-142 is not assigned to you" (ASSUMPTION for the text; Jira gives the 403). The BPA-6
 * reference lists ({@code /api/v1/activities} and the others) are not touched: BPA-78 scopes Work Pack creation with the same {@link ProjectScope}.
 */
@RestController
public class ProjectMasterDataController implements ProjectMasterDataApi {

    /** The real systems of record, as docs/03-sor-matrix.md names them. */
    static final String ACTIVITIES_SOR = "P6, read through the D365 PMS database";
    static final String COST_STRUCTURE_SOR = "D365 F&O Cost Control Report";
    static final String DRAWINGS_SOR = "D365 F&O Document Control";
    static final String RESERVED_MATERIALS_SOR = "D365 F&O (PPC)";
    static final String CUTTING_SHEETS_SOR = "FMS nesting";

    private final ProjectScope scope;
    private final ProjectMasterDataRepository masterData;

    public ProjectMasterDataController(ProjectScope scope, ProjectMasterDataRepository masterData) {
        this.scope = scope;
        this.masterData = masterData;
    }

    @Override
    public ResponseEntity<List<Project>> listMyProjects() {
        return ResponseEntity.ok(scope.projectsOf(sessionUser()).stream().map(Project::new).toList());
    }

    @Override
    public ResponseEntity<ProjectMasterData> getProjectMasterData(String projectRef) {
        scope.require(sessionUser(), projectRef);
        return ResponseEntity.ok(new ProjectMasterData(projectRef,
                new ActivitiesTab(ACTIVITIES_SOR, masterData.activities(projectRef)),
                new CostStructureTab(COST_STRUCTURE_SOR, masterData.costStructure(projectRef)),
                new DrawingsTab(DRAWINGS_SOR, masterData.drawings(projectRef)),
                new ReservedMaterialsTab(RESERVED_MATERIALS_SOR, masterData.reservedMaterials(projectRef)),
                new CuttingSheetsTab(CUTTING_SHEETS_SOR, masterData.cuttingSheets(projectRef))));
    }

    @ExceptionHandler(ProjectNotAssignedException.class)
    ResponseEntity<ProblemDetail> notAssigned(ProjectNotAssignedException e) {
        HttpStatus status = HttpStatus.FORBIDDEN;
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(ProblemDetail.forStatusAndDetail(status, e.getMessage()));
    }

    /** The Camunda username of the session user (D63). */
    private static String sessionUser() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
