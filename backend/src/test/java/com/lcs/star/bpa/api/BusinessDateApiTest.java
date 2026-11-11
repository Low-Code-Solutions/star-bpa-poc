package com.lcs.star.bpa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.lcs.star.bpa.adapter.CamundaIdentityClient;
import com.lcs.star.bpa.adapter.CamundaIdentityClient.CamundaUser;
import com.lcs.star.bpa.adapter.CamundaProcessClient;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BPA-82: {@code GET /api/v1/business-date} answers the date the server's injected clock gives in the business zone (S9, ADR 0013), so the portal
 * can check dates against the same "today" as the validators, including when QA has set a business date (BPA-81). Camunda's side of the login is a
 * mock, as in {@link AuthApiTest}; the clock is replaced by a settable {@code @Primary} bean, as the other API tests do. Not Gherkin (the task has
 * none); the scenarios are the plan's, marked ASSUMPTION.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "camunda.client.enabled=false")
@Testcontainers
@Import(BusinessDateApiTest.TestClockConfig.class)
class BusinessDateApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @LocalServerPort
    int port;

    @MockitoBean
    CamundaIdentityClient camunda;

    @MockitoBean
    CamundaProcessClient processes;

    @Autowired
    SettableClock clock;

    TestBrowser browser;

    @BeforeEach
    void user() {
        when(camunda.verify("time-method-analyst", "right")).thenReturn(Optional.of(new CamundaUser("time-method-analyst", List.of("time-method-analyst"))));
        browser = new TestBrowser(port);
    }

    private HttpResponse<String> businessDateAsLoggedInUser() {
        assertThat(browser.login("time-method-analyst", "right").statusCode()).isEqualTo(200);
        return browser.get("/api/v1/business-date");
    }

    @Test
    void BPA82_businessDateIsTheRiyadhDateFromTheClock() {
        // 21:30 UTC on 21 Oct is 00:30 on 22 Oct in Riyadh (UTC+3): the UTC date and the business date differ
        clock.set(Instant.parse("2026-10-21T21:30:00Z"));

        HttpResponse<String> response = businessDateAsLoggedInUser();

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualToIgnoringWhitespace("{\"date\":\"2026-10-22\",\"zone\":\"Asia/Riyadh\"}");
    }

    @Test
    void BPA82_businessDateFollowsAReplacedClock() {
        // the QA case (BPA-81): the clock runs from 1 Dec 2026 whatever the real date is
        clock.set(Instant.parse("2026-12-01T06:00:00Z"));

        HttpResponse<String> response = businessDateAsLoggedInUser();

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualToIgnoringWhitespace("{\"date\":\"2026-12-01\",\"zone\":\"Asia/Riyadh\"}");
    }

    @Test
    void BPA82_businessDateNeedsALogin() {
        assertThat(browser.get("/api/v1/business-date").statusCode()).isEqualTo(401);
    }

    /** A clock the test can move, as the API tests use for "Given today is …" (S9). */
    static final class SettableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-10-21T06:00:00Z");

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        SettableClock settableClock() {
            return new SettableClock();
        }
    }
}
