package com.lcs.star.bpa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lcs.star.bpa.adapter.CamundaIdentityClient;
import com.lcs.star.bpa.adapter.CamundaProcessClient;
import com.lcs.star.bpa.adapter.CamundaIdentityClient.CamundaUser;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.ResourceAccessException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BPA-43 Part B: login, /me, logout, CSRF and the guarded Work Pack start (S5, S26, S27, ADR 0005; Q6). Camunda's side of the
 * login is replaced by a mock here; BPA25_check3GroupsAreReadAtLogin proves it against a real Camunda.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "camunda.client.enabled=false")
@Testcontainers
class AuthApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @LocalServerPort
    int port;

    @MockitoBean
    CamundaIdentityClient camunda;

    /** BPA-6: the process start is Camunda's side too; this class tests the gate in front of it. */
    @MockitoBean
    CamundaProcessClient processes;

    TestBrowser browser;

    @BeforeEach
    void users() {
        when(camunda.verify("time-method-analyst", "right")).thenReturn(Optional.of(new CamundaUser("time-method-analyst", List.of("time-method-analyst"))));
        when(camunda.verify("production-team", "right")).thenReturn(Optional.of(new CamundaUser("production-team", List.of("production-team"))));
        browser = new TestBrowser(port);
    }

    @Test
    void BPA43_loginSetsAnHttpOnlySessionCookie() {
        HttpResponse<String> response = browser.login("time-method-analyst", "right");

        assertThat(response.statusCode()).isEqualTo(200);
        List<String> setCookies = response.headers().allValues("Set-Cookie");
        assertThat(setCookies).filteredOn(c -> c.startsWith("JSESSIONID=")).singleElement().satisfies(session -> {
            assertThat(session).containsIgnoringCase("HttpOnly");
            assertThat(session).containsIgnoringCase("SameSite=Lax");
        });
        assertThat(response.body()).doesNotContain("right");
    }

    @Test
    void BPA43_wrongPasswordIsRefused() {
        HttpResponse<String> response = browser.login("time-method-analyst", "wrong");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(t -> assertThat(t).startsWith("application/problem+json"));
        assertThat(browser.get("/api/v1/me").statusCode()).isEqualTo(401);
    }

    @Test
    void BPA43_meReturnsUsernameAndGroups() {
        browser.login("time-method-analyst", "right");

        HttpResponse<String> me = browser.get("/api/v1/me");

        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body()).isEqualToIgnoringWhitespace("{\"username\":\"time-method-analyst\",\"groups\":[\"time-method-analyst\"]}");
    }

    @Test
    void BPA43_logoutEndsTheSession() {
        browser.login("time-method-analyst", "right");

        assertThat(browser.post("/api/v1/auth/logout", "").statusCode()).isEqualTo(204);

        assertThat(browser.get("/api/v1/me").statusCode()).isEqualTo(401);
    }

    @Test
    void BPA43_stateChangingCallWithoutCsrfTokenIsRejected() {
        browser.get("/api/v1/me");
        assertThat(browser.cookie("XSRF-TOKEN")).as("token cookie issued by a GET").isNotBlank();

        HttpResponse<String> response = browser.postWithoutCsrfToken("/api/v1/auth/login", "{\"username\":\"time-method-analyst\",\"password\":\"right\"}");

        assertThat(response.statusCode()).isEqualTo(403);
        verify(camunda, never()).verify(any(), any());
    }

    @Test
    void BPA43_startWorkPackIsDeniedToOtherGroups403() {
        browser.login("production-team", "right");

        HttpResponse<String> response = browser.post("/api/v1/workpacks", "{}");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(t -> assertThat(t).startsWith("application/problem+json"));
        assertThat(response.body()).as("refused for the group, not for a missing CSRF token").contains("groups do not allow");
    }

    @Test
    void BPA43_camundaUnreachableAtLoginReturns503() {
        doThrow(new ResourceAccessException("Connection refused")).when(camunda).verify("time-method-analyst", "any");

        HttpResponse<String> response = browser.login("time-method-analyst", "any");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(t -> assertThat(t).startsWith("application/problem+json"));
        assertThat(response.body()).doesNotContain("Connection refused");
    }

    @Test
    void BPA43_csrfTokenAndSessionChangeAtLogin() {
        browser.get("/api/v1/me");
        String tokenBefore = browser.cookie("XSRF-TOKEN");

        browser.post("/api/v1/auth/login", "{\"username\":\"time-method-analyst\",\"password\":\"right\"}");
        String sessionAfterFirst = browser.cookie("JSESSIONID");
        browser.post("/api/v1/auth/login", "{\"username\":\"production-team\",\"password\":\"right\"}");

        assertThat(browser.cookie("XSRF-TOKEN")).isNotBlank().isNotEqualTo(tokenBefore);
        assertThat(browser.cookie("JSESSIONID")).isNotBlank().isNotEqualTo(sessionAfterFirst);
        assertThat(browser.get("/api/v1/me").body()).contains("production-team");
    }

    @Test
    void BPA43_loggedInPostWithoutCsrfTokenIsRejected() {
        browser.login("time-method-analyst", "right");

        assertThat(browser.postWithoutCsrfToken("/api/v1/workpacks", "{}").statusCode()).isEqualTo(403);
    }

    /**
     * Was BPA43_startWorkPackReturns501ForTimeMethodAnalyst: the skeleton's 501 stub, which BPA-6 replaced with the real start
     * (said in the BPA-6 PR, L15). What the skeleton guarded still holds: the analyst passes the group gate.
     */
    @Test
    void BPA43_startWorkPackIsAllowedForTimeMethodAnalyst() {
        when(processes.start("Process_PPC_WorkPackAssignment")).thenReturn("2251799813685501");
        browser.login("time-method-analyst", "right");

        HttpResponse<String> response = browser.post("/api/v1/workpacks", "{}");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).contains("2251799813685501");
    }
}
