package com.lcs.star.bpa.scope;

/** The user has no user_project row for the project, or the project does not exist (the same answer; the controllers map it to 403). */
public class ProjectNotAssignedException extends RuntimeException {

    public ProjectNotAssignedException(String projectRef) {
        super("Project " + projectRef + " is not assigned to you");
    }
}
