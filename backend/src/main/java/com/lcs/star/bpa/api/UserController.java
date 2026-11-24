package com.lcs.star.bpa.api;

import com.lcs.star.bpa.adapter.CamundaGroupClient;
import com.lcs.star.bpa.adapter.CamundaTaskClient.EngineUnavailableException;
import com.lcs.star.bpa.api.generated.UsersApi;
import com.lcs.star.bpa.api.generated.model.NewUserProject;
import com.lcs.star.bpa.api.generated.model.PortalUser;
import com.lcs.star.bpa.api.generated.model.Project;
import com.lcs.star.bpa.api.generated.model.UserProject;
import com.lcs.star.bpa.config.PersonaSync;
import com.lcs.star.bpa.persistence.AppUserRepository;
import com.lcs.star.bpa.persistence.ReferenceRepository;
import com.lcs.star.bpa.persistence.UserProjectRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;

/**
 * Portal administration for the PPC Team (BPA-76; ADR 0005): the portal users with their Camunda groups, the projects that can be assigned, and a user's projects
 * (assign, list, unassign). Every method of every path is ppc-team only (SecurityConfig, 403). The rows are {@code user_project}, written here as the session
 * user and audited per business column (D64); the persona sync is the other writer, and only while a user has no row. Nothing is scoped by the rows yet
 * (BPA-77 to BPA-79). Messages: "Project PRJ-999 does not exist" and "PRJ-139 is already assigned to project-engineer" are Jira's (both 400); "User not found",
 * "PRJ-140 is not assigned to project-engineer" (404) and the 503 text are ASSUMPTIONs (the contract says so).
 */
@RestController
public class UserController implements UsersApi {

    private static final Logger LOG = LoggerFactory.getLogger(UserController.class);

    private final AppUserRepository users;
    private final UserProjectRepository userProjects;
    private final ReferenceRepository references;
    private final CamundaGroupClient groups;
    private final Clock clock;

    public UserController(AppUserRepository users, UserProjectRepository userProjects, ReferenceRepository references, CamundaGroupClient groups, Clock clock) {
        this.users = users;
        this.userProjects = userProjects;
        this.references = references;
        this.groups = groups;
        this.clock = clock;
    }

    /** Each user with the groups Camunda says it is in: one member search per group (eleven), inverted. A search Camunda cannot answer is a 503, never a user with a group missing. */
    @Override
    public ResponseEntity<List<PortalUser>> listPortalUsers() {
        Map<String, List<String>> groupsOf = new HashMap<>();
        for (String group : PersonaSync.GROUPS) {
            for (String username : groups.usernames(group)) {
                groupsOf.computeIfAbsent(username, k -> new ArrayList<>()).add(group);
            }
        }
        return ResponseEntity.ok(users.all().stream()
                .map(u -> new PortalUser(u.username(), u.employeeRef(), u.email(), groupsOf.getOrDefault(u.username(), List.of())))
                .toList());
    }

    @Override
    public ResponseEntity<List<Project>> listProjects() {
        return ResponseEntity.ok(references.projects());
    }

    @Override
    public ResponseEntity<List<Project>> listUserProjects(String username) {
        requireUser(username);
        return ResponseEntity.ok(userProjects.projectsOf(username).stream().map(Project::new).toList());
    }

    /** User, then project, then duplicate: the first thing that is wrong is the one the PPC Team is told. */
    @Override
    public ResponseEntity<UserProject> assignProject(String username, NewUserProject newUserProject) {
        requireUser(username);
        String projectRef = newUserProject.getProjectRef();
        // A blank reference, or one with a control character (a JSON NUL would reach Postgres as an invalid byte sequence and answer 500), cannot be a project.
        if (!isPlausibleRef(projectRef) || !references.projectExists(projectRef)) {
            throw refused(HttpStatus.BAD_REQUEST, "Project " + projectRef + " does not exist");
        }
        if (!userProjects.assign(username, projectRef, sessionUser(), clock.instant().truncatedTo(ChronoUnit.MICROS))) {
            throw refused(HttpStatus.BAD_REQUEST, projectRef + " is already assigned to " + username);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(new UserProject(username, projectRef));
    }

    @Override
    public ResponseEntity<Void> unassignProject(String username, String projectRef) {
        requireUser(username);
        if (!userProjects.unassign(username, projectRef, sessionUser(), clock.instant().truncatedTo(ChronoUnit.MICROS))) {
            throw refused(HttpStatus.NOT_FOUND, projectRef + " is not assigned to " + username);
        }
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(EngineUnavailableException.class)
    ResponseEntity<ProblemDetail> engineUnavailable(EngineUnavailableException e) {
        LOG.warn("Users not listed: {}", e.getMessage());
        HttpStatus status = HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemDetail.forStatusAndDetail(status, "The process engine is not available right now; try again later"));
    }

    private void requireUser(String username) {
        if (!users.exists(username)) {
            throw refused(HttpStatus.NOT_FOUND, "User not found");
        }
    }

    private static boolean isPlausibleRef(String projectRef) {
        return projectRef != null && !projectRef.isBlank() && projectRef.chars().noneMatch(Character::isISOControl);
    }

    private static ErrorResponseException refused(HttpStatus status, String detail) {
        return new ErrorResponseException(status, ProblemDetail.forStatusAndDetail(status, detail), null);
    }

    /** The Camunda username of the session user, as every "done by" field stores it (D63). */
    private static String sessionUser() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
