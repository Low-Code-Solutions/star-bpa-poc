package com.lcs.star.bpa.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lcs.star.bpa.adapter.CamundaGroupClient;
import com.lcs.star.bpa.config.AlertSettings;
import com.lcs.star.bpa.persistence.NotificationRepository;
import com.lcs.star.bpa.persistence.ProgressPeriodRepository.Period;
import com.lcs.star.bpa.persistence.VarianceAlertRepository;
import com.lcs.star.bpa.progress.VarianceAlertService.Over;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * BPA-64, QA review: who the man-hour alert goes to, with mocks (no database, no Camunda). An over-forecast CBS that has nobody to alert (the Cost Controller group
 * answers no member, as when bpa-service lacks GROUP READ, and no PCON Manager address is set) fails the job loudly instead of completing with nothing sent and no alert
 * recorded: the "alert raised" fact, and with it the remark rule, comes from the recorded alert rows. A Cost Controller with no app_user row cannot be recorded (the
 * recipient is a foreign key), so that user is skipped with a warning, as BPA-16 skips a recipient with no email.
 */
class VarianceAlertServiceTest {

    private final VarianceAlertRepository alerts = mock(VarianceAlertRepository.class);
    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final CamundaGroupClient groups = mock(CamundaGroupClient.class);
    private final JavaMailSender mail = mock(JavaMailSender.class);
    private final AlertSettings settings = mock(AlertSettings.class);
    private final Period period = new Period(UUID.randomUUID(), LocalDate.of(2026, 11, 19));
    private final List<Over> overs = List.of(new Over("CBS-139-PIP-01", new BigDecimal("1200.00"), new BigDecimal("1000.00")));
    private VarianceAlertService service;

    @BeforeEach
    void service() {
        service = new VarianceAlertService(alerts, notifications, groups, mail, settings, Clock.fixed(Instant.parse("2026-11-20T07:00:00Z"), ZoneOffset.UTC), "star-bpa@example.com");
    }

    @Test
    void anOverForecastCbsWithNobodyToAlertFailsLoudly() {
        when(groups.usernames("cost-controller")).thenReturn(List.of());
        when(settings.pconManagerEmail()).thenReturn("");

        assertThatThrownBy(() -> service.sendManhourAlerts(period, overs, "bpa-service")).isInstanceOf(IllegalStateException.class).hasMessageContaining("No recipient");

        verifyNoInteractions(mail);
        verify(alerts, never()).record(any(), anyString(), anyString(), any(), any(), any(), anyString(), anyBoolean(), anyString(), anyString(), any());
    }

    @Test
    void aControllerWithNoAppUserRowIsSkippedWithoutAnError() {
        when(groups.usernames("cost-controller")).thenReturn(List.of("ghost"));
        when(notifications.emails(any())).thenReturn(Map.of());
        when(settings.pconManagerEmail()).thenReturn("pcon@star.example");

        service.sendManhourAlerts(period, overs, "bpa-service");

        verify(alerts, never()).record(any(), anyString(), anyString(), any(), any(), any(), eq("ghost"), eq(false), anyString(), anyString(), any());
        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(sent.capture());
        assertThat(sent.getValue().getTo()).containsExactly("pcon@star.example");
    }

    // --- BPA-65: the cost alert goes to project-manager, never to cost-controller ------------------------------------------------

    private final List<VarianceAlertService.CostOver> costOvers = List.of(new VarianceAlertService.CostOver("CBS-139-PIP-01", new BigDecimal("300000.00"), new BigDecimal("250000.00")));

    @Test
    void aCostAlertWithNobodyToAlertFailsLoudly() {
        when(groups.usernames("project-manager")).thenReturn(List.of());
        when(settings.pconManagerEmail()).thenReturn("");

        assertThatThrownBy(() -> service.sendCostAlerts(period, LocalDate.of(2026, 11, 20), costOvers, "bpa-service")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No recipient");

        verifyNoInteractions(mail);
    }

    @Test
    void aCostAlertAsksTheProjectManagerGroupOnly() {
        when(groups.usernames("project-manager")).thenReturn(List.of("project-manager"));
        when(notifications.emails(any())).thenReturn(Map.of("project-manager", "project-manager@example.com"));
        when(settings.pconManagerEmail()).thenReturn("pcon@star.example");

        service.sendCostAlerts(period, LocalDate.of(2026, 11, 20), costOvers, "bpa-service");

        verify(groups, never()).usernames("cost-controller");
        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail, org.mockito.Mockito.times(2)).send(sent.capture());
        assertThat(sent.getAllValues()).extracting(m -> m.getTo()[0]).containsExactlyInAnyOrder("project-manager@example.com", "pcon@star.example");
        assertThat(sent.getAllValues().get(0).getText()).isEqualTo("Cost on CBS-139-PIP-01 is 20.00% over the Revised Budget (300000.00 of 250000.00 SAR), 20 Nov 2026");
    }
}
