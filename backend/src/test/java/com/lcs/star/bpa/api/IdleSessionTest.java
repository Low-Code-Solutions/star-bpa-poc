package com.lcs.star.bpa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.lcs.star.bpa.adapter.CamundaIdentityClient;
import com.lcs.star.bpa.adapter.CamundaIdentityClient.CamundaUser;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BPA-43 Part B: an idle session ends and the API answers 401 (S27). The 30-minute timeout is checked as configured; the
 * expiry itself is run with a one-second session so the test does not wait half an hour.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "camunda.client.enabled=false")
@Testcontainers
class IdleSessionTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @LocalServerPort
    int port;

    @Value("${server.servlet.session.timeout}")
    Duration configuredTimeout;

    @MockitoBean
    CamundaIdentityClient camunda;

    @TestConfiguration
    static class OneSecondSessions {
        @Bean
        ServletListenerRegistrationBean<HttpSessionListener> oneSecondSessions() {
            return new ServletListenerRegistrationBean<>(new HttpSessionListener() {
                @Override
                public void sessionCreated(HttpSessionEvent event) {
                    event.getSession().setMaxInactiveInterval(1);
                }
            });
        }
    }

    @Test
    void BPA43_idleSessionReturns401() throws InterruptedException {
        assertThat(configuredTimeout).isEqualTo(Duration.ofMinutes(30));
        when(camunda.verify("tma-1", "right")).thenReturn(Optional.of(new CamundaUser("tma-1", List.of("time-method-analyst"))));
        var browser = new TestBrowser(port);
        assertThat(browser.login("tma-1", "right").statusCode()).isEqualTo(200);
        assertThat(browser.get("/api/v1/me").statusCode()).isEqualTo(200);

        Thread.sleep(2500);

        var me = browser.get("/api/v1/me");
        assertThat(me.statusCode()).isEqualTo(401);
        assertThat(me.headers().firstValue("Content-Type")).hasValueSatisfying(t -> assertThat(t).startsWith("application/problem+json"));
    }
}
