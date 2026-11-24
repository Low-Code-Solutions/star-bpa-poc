package com.lcs.star.bpa.scope;

import com.lcs.star.bpa.persistence.UserProjectRepository;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The one project-scope rule (BPA-77; reused by BPA-78, Work Pack creation, and BPA-79, the Work Pack list and detail): a user may read project P only when a
 * {@code user_project} row (username, P) exists (BPA-76 lets the PPC Team assign them). One rule for every persona, ppc-team included: there is no group exception
 * (plan BPA-77, resolved Q3). A project that does not exist has no row, so it is refused like an unassigned one and the answer does not say whether the project
 * exists (ASSUMPTION). Controllers map {@link ProjectNotAssignedException} to 403 with the text "Project PRJ-142 is not assigned to you" (ASSUMPTION: Jira gives
 * only the 403).
 */
@Component
public class ProjectScope {

    private final UserProjectRepository userProjects;

    public ProjectScope(UserProjectRepository userProjects) {
        this.userProjects = userProjects;
    }

    /** The project references of the user, by reference; empty when the user has none. */
    public List<String> projectsOf(String username) {
        return userProjects.projectsOf(username);
    }

    /** True when a row (username, projectRef) exists; for filtering a list (BPA-79). */
    public boolean allows(String username, String projectRef) {
        return userProjects.exists(username, projectRef);
    }

    /** Passes for an assigned project; otherwise throws {@link ProjectNotAssignedException}. */
    public void require(String username, String projectRef) {
        if (!allows(username, projectRef)) {
            throw new ProjectNotAssignedException(projectRef);
        }
    }
}
