package com.lcs.star.bpa.scope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lcs.star.bpa.persistence.UserProjectRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * BPA-77: the shared project-scope check (plan BPA-77, design 1) that BPA-78 and BPA-79 reuse. A user reads project P only when a user_project row
 * (username, P) exists; one rule for every persona, with no group exception (resolved Q3).
 */
class ProjectScopeTest {

    private final UserProjectRepository rows = mock(UserProjectRepository.class);
    private final ProjectScope scope = new ProjectScope(rows);

    @Test
    void projectsOfGivesTheUsersRowsByReference() {
        when(rows.projectsOf("time-method-analyst")).thenReturn(List.of("PRJ-139", "PRJ-142"));

        assertThat(scope.projectsOf("time-method-analyst")).containsExactly("PRJ-139", "PRJ-142");
    }

    @Test
    void projectsOfAUserWithNoRowsIsEmpty() {
        when(rows.projectsOf("supervisor")).thenReturn(List.of());

        assertThat(scope.projectsOf("supervisor")).isEmpty();
    }

    @Test
    void allowsOnlyWhenARowExists() {
        when(rows.exists("project-engineer", "PRJ-139")).thenReturn(true);
        when(rows.exists("project-engineer", "PRJ-142")).thenReturn(false);

        assertThat(scope.allows("project-engineer", "PRJ-139")).isTrue();
        assertThat(scope.allows("project-engineer", "PRJ-142")).isFalse();
    }

    @Test
    void requirePassesForAnAssignedProject() {
        when(rows.exists("project-engineer", "PRJ-139")).thenReturn(true);

        scope.require("project-engineer", "PRJ-139"); // no exception
    }

    @Test
    void requireRefusesAProjectThatIsNotAssignedWithTheTextOfTheContract() {
        when(rows.exists("project-engineer", "PRJ-142")).thenReturn(false);

        assertThatThrownBy(() -> scope.require("project-engineer", "PRJ-142"))
                .isInstanceOf(ProjectNotAssignedException.class)
                .hasMessage("Project PRJ-142 is not assigned to you");
    }

    /** One rule for every persona (resolved Q3): ppc-team is not an exception, it needs its own row like anyone. */
    @Test
    void thePpcTeamHasNoSpecialCase() {
        when(rows.exists("ppc-team", "PRJ-139")).thenReturn(false);

        assertThat(scope.allows("ppc-team", "PRJ-139")).isFalse();
    }
}
