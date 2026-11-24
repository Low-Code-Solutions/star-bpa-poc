package com.lcs.star.bpa.scope;

import com.lcs.star.bpa.persistence.UserProjectRepository;
import java.util.List;
import org.springframework.stereotype.Component;

/** COMPILE STUB (BPA-77, red tests first): the real check is written after the tests have been shown failing. */
@Component
public class ProjectScope {

    private final UserProjectRepository userProjects;

    public ProjectScope(UserProjectRepository userProjects) {
        this.userProjects = userProjects;
    }

    public List<String> projectsOf(String username) {
        throw new UnsupportedOperationException("BPA-77 stub");
    }

    public boolean allows(String username, String projectRef) {
        throw new UnsupportedOperationException("BPA-77 stub");
    }

    public void require(String username, String projectRef) {
        throw new UnsupportedOperationException("BPA-77 stub");
    }
}
