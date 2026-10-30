package com.lcs.star.bpa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lcs.star.bpa.adapter.CamundaIdentityClient;
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

    TestBrowser browser;

    @BeforeEach
    void users() {
        when(camunda.verify("tma-1", "right")).thenReturn(Optional.of(new CamundaUser("tma-1", List.of("time-method-analyst"))));
        when(camunda.verify("pt-1", "right")).thenReturn(Optional.of(new CamundaUser("pt-1", List.of("production-team"))));
        browser = new TestBrowser(port);
    }

    @Test
    void BPA43_loginSetsAnHttpOnlySessionCookie() {
        HttpResponse<String> response = browser.login("tma-1", "right");

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
        HttpResponse<String> response = browser.login("tma-1", "wrong");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(t -> assertThat(t).startsWith("application/problem+json"));
        assertThat(browser.get("/api/v1/me").statusCode()).isEqualTo(401);
    }

    @Test
    void BPA43_meReturnsUsernameAndGroups() {
        browser.login("tma-1", "right");

        HttpResponse<String> me = browser.get("/api/v1/me");

        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body()).isEqualToIgnoringWhitespace("{\"username\":\"tma-1\",\"groups\":[\"time-method-analyst\"]}");
    }

    @Test
    void BPA43_logoutEndsTheSession() {
        browser.login("tma-1", "right");

        assertThat(browser.post("/api/v1/auth/logout", "").statusCode()).isEqualTo(204);

        assertThat(browser.get("/api/v1/me").statusCode()).isEqualTo(401);
    }

    @Test
    void BPA43_stateChangingCallWithoutCsrfTokenIsRejected() {
        browser.get("/api/v1/me");
        assertThat(browser.cookie("XSRF-TOKEN")).as("token cookie issued by a GET").isNotBlank();

        HttpResponse<String> response = browser.postWithoutCsrfToken("/api/v1/auth/login", "{\"username\":\"tma-1\",\"password\":\"right\"}");

        assertThat(response.statusCode()).isEqualTo(403);
        verify(camunda, never()).verify(any(), any());
    }

    @Test
    void BPA43_startWorkPackIsDeniedToOtherGroups403() {
        browser.login("pt-1", "right");

        HttpResponse<String> response = browser.post("/api/v1/workpacks", "{}");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(t -> assertThat(t).startsWith("application/problem+json"));
        assertThat(response.body()).as("refused for the group, not for a missing CSRF token").contains("groups do not allow");
    }

    @Test
    void BPA43_camundaUnreachableAtLoginReturns503() {
        doThrow(new ResourceAccessException("Connection refused")).when(camunda).verify("tma-1", "any");

        HttpResponse<String> response = browser.login("tma-1", "any");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(t -> assertThat(t).startsWith("application/problem+json"));
        assertThat(response.body()).doesNotContain("Connection refused");
    }

    @Test
    void BPA43_csrfTokenAndSessionChangeAtLogin() {
        browser.get("/api/v1/me");
        String tokenBefore = browser.cookie("XSRF-TOKEN");

        browser.post("/api/v1/auth/login", "{\"username\":\"tma-1\",\"password\":\"right\"}");
        String sessionAfterFirst = browser.cookie("JSESSIONID");
        browser.post("/api/v1/auth/login", "{\"username\":\"pt-1\",\"password\":\"right\"}");

        assertThat(browser.cookie("XSRF-TOKEN")).isNotBlank().isNotEqualTo(tokenBefore);
        assertThat(browser.cookie("JSESSIONID")).isNotBlank().isNotEqualTo(sessionAfterFirst);
        assertThat(browser.get("/api/v1/me").body()).contains("pt-1");
    }

    @Test
    void BPA43_loggedInPostWithoutCsrfTokenIsRejected() {
        browser.login("tma-1", "right");

        assertThat(browser.postWithoutCsrfToken("/api/v1/workpacks", "{}").statusCode()).isEqualTo(403);
    }

    @Test
    void BPA43_startWorkPackReturns501ForTimeMethodAnalyst() {
        browser.login("tma-1", "right");

        assertThat(browser.post("/api/v1/workpacks", "{}").statusCode()).isEqualTo(501);
    }
}
