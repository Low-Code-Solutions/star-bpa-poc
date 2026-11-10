package com.lcs.star.bpa.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lcs.star.bpa.persistence.NotificationRepository;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * BPA-16, not a Gherkin scenario: {@code send-revision-notice} without a database or an engine. A retried job must not send the email twice once an Email
 * row exists (D44, 09 S7), the email text and time zone are what the story says, and a job with incomplete variables fails with no retries instead of
 * looping. The scenarios are in DrawingRevisionApiTest.
 */
class SendRevisionNoticeWorkerTest {

    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final JavaMailSender mail = mock(JavaMailSender.class);
    private final JobClient client = mock(JobClient.class, Mockito.RETURNS_DEEP_STUBS);
    private final SendRevisionNoticeWorker worker = new SendRevisionNoticeWorker(notifications, mail,
            Clock.fixed(Instant.parse("2026-10-30T06:00:00Z"), ZoneOffset.UTC), ZoneId.of("Asia/Riyadh"), "star-bpa@example.com");

    private static Map<String, Object> variables() {
        Map<String, Object> variables = new HashMap<>();
        variables.put("recipient", Map.of("username", "workpack-owner", "email", "workpack-owner@example.com"));
        variables.put("workpacks", List.of(Map.of("id", UUID.randomUUID().toString(), "number", "WP-000457")));
        variables.put("drawingRef", "DWG-118");
        variables.put("revision", "C");
        variables.put("status", "IFC");
        variables.put("registeredAt", "2026-10-28T06:30:00Z");
        variables.put("supersededRevision", "B");
        return variables;
    }

    private static ActivatedJob job(Map<String, Object> variables) {
        ActivatedJob job = mock(ActivatedJob.class);
        when(job.getKey()).thenReturn(1L);
        when(job.getVariablesAsMap()).thenReturn(variables);
        return job;
    }

    @Test
    void aRetryAfterTheEmailWentOutSendsNothingMore() {
        when(notifications.emailRecorded("workpack-owner", "DWG-118", "C")).thenReturn(true);

        worker.handle(client, job(variables()));

        verify(mail, never()).send(any(SimpleMailMessage.class));
        verify(notifications).record(eq(NotificationRepository.IN_SYSTEM), anyList(), eq("DWG-118"), eq("C"), eq("workpack-owner"), any());
        verify(notifications, never()).record(eq(NotificationRepository.EMAIL), anyList(), anyString(), anyString(), anyString(), any());
        verify(client).newCompleteCommand(1L);
    }

    @Test
    void theEmailHasTheSubjectTheListedFieldsAndTheTimeInRiyadh() {
        when(notifications.emailRecorded(anyString(), anyString(), anyString())).thenReturn(false);

        worker.handle(client, job(variables()));

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(sent.capture());
        assertThat(sent.getValue().getTo()).containsExactly("workpack-owner@example.com");
        assertThat(sent.getValue().getFrom()).isEqualTo("star-bpa@example.com");
        assertThat(sent.getValue().getSubject()).isEqualTo("Drawing DWG-118 revised to C");
        assertThat(sent.getValue().getText()).contains("Work Pack(s): WP-000457", "Drawing: DWG-118", "Previous revision: B", "New revision: C", "Status: IFC",
                "Registered at: 28 Oct 2026 09:30 (Asia/Riyadh)");
        verify(notifications).record(eq(NotificationRepository.EMAIL), anyList(), eq("DWG-118"), eq("C"), eq("workpack-owner"), any());
    }

    @Test
    void withoutASupersededRevisionThePreviousRevisionReadsNone() {
        when(notifications.emailRecorded(anyString(), anyString(), anyString())).thenReturn(false);
        Map<String, Object> variables = variables();
        variables.remove("supersededRevision");

        worker.handle(client, job(variables));

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(sent.capture());
        assertThat(sent.getValue().getText()).contains("Previous revision: none");
    }

    @Test
    void aJobWithoutItsRecipientFailsWithNoRetriesAndSendsNothing() {
        Map<String, Object> variables = variables();
        variables.remove("recipient");

        worker.handle(client, job(variables));

        verify(mail, never()).send(any(SimpleMailMessage.class));
        verify(notifications, never()).record(anyString(), anyList(), anyString(), anyString(), anyString(), any());
        verify(client).newFailCommand(1L);
        verify(client, never()).newCompleteCommand(Mockito.anyLong());
    }
}
