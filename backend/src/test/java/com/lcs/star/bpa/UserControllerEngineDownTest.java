package com.lcs.star.bpa;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lcs.star.bpa.adapter.CamundaGroupClient;
import com.lcs.star.bpa.adapter.CamundaTaskClient.EngineUnavailableException;
import com.lcs.star.bpa.api.UserController;
import com.lcs.star.bpa.persistence.AppUserRepository;
import com.lcs.star.bpa.persistence.ReferenceRepository;
import com.lcs.star.bpa.persistence.UserProjectRepository;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * BPA-76, QA review: {@code GET /api/v1/users} reads the members of eleven Camunda groups, and a group Camunda cannot answer for must make the call a 503 with
 * the contract's problem text, never a list with a group missing. The controller alone is built (no database, no Camunda), with a group client that fails, so
 * the controller's own exception handler is what answers. The text is an ASSUMPTION (the contract's x-assumption: no story states it).
 */
class UserControllerEngineDownTest {

    @Test
    void aGroupCamundaCannotAnswerForMakesTheUsersListA503() throws Exception {
        CamundaGroupClient groups = mock(CamundaGroupClient.class);
        when(groups.usernames(anyString())).thenThrow(new EngineUnavailableException("Camunda group member search for supervisor returned 500"));
        AppUserRepository users = mock(AppUserRepository.class);
        when(users.all()).thenReturn(List.of(new AppUserRepository.AppUser("supervisor", "E9006", "supervisor@example.com")));
        UserController controller = new UserController(users, mock(UserProjectRepository.class), mock(ReferenceRepository.class), groups, Clock.systemUTC());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/api/v1/users"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.detail").value("The process engine is not available right now; try again later"));
    }
}
