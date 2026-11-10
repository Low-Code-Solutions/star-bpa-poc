package com.lcs.star.bpa.worker;

import com.lcs.star.bpa.persistence.NotificationRepository;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Job type {@code send-revision-notice} on {@code SendTask_NotifyRecipients} of Process_PPC_DrawingRevisionNotice (BPA-16; D44, D46, 09 S7, S8, S9).
 * One job per recipient (parallel multi-instance over {@code recipients}): it stores the in-system notice for each Work Pack in {@code workpacks},
 * then sends one email to the recipient listing those Work Packs, and records the email, one row per Work Pack. The in-system notice is committed
 * first, so it survives a mail server that is down. Idempotent on the notification unique key and on "an Email row for this recipient and revision
 * already exists" (data model 5.9): a retry writes nothing twice and sends no second email, except when the process stops between the send and the
 * record (at-least-once, ASSUMPTION). A mail failure is not caught: Camunda's defaults apply, 3 tries immediately, then an incident (S7, ADR 0007).
 *
 * <p>The email (ASSUMPTION, no source gives the layout): subject "Drawing {drawingRef} revised to {revision}"; plain text with the Work Pack numbers, the
 * drawing, the previous revision (the supersededRevision process variable, never stored, D68), the new revision, the status and the registration time
 * in Asia/Riyadh (S9). The sender address is configuration ({@code bpa.mail.from}).
 */
@Component
public class SendRevisionNoticeWorker {

    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.ENGLISH);

    private static final Logger LOG = LoggerFactory.getLogger(SendRevisionNoticeWorker.class);

    private final NotificationRepository notifications;
    private final JavaMailSender mail;
    private final Clock clock;
    private final ZoneId zone;
    private final String from;

    public SendRevisionNoticeWorker(NotificationRepository notifications, JavaMailSender mail, Clock clock, ZoneId businessZone,
            @Value("${bpa.mail.from:star-bpa@example.com}") String from) {
        this.notifications = notifications;
        this.mail = mail;
        this.clock = clock;
        this.zone = businessZone;
        this.from = from;
    }

    @JobWorker(type = "send-revision-notice", autoComplete = false)
    @SuppressWarnings("unchecked")
    public void handle(JobClient client, ActivatedJob job) {
        Map<String, Object> variables = job.getVariablesAsMap();
        try {
            Map<String, Object> recipient = (Map<String, Object>) variables.get("recipient");
            String username = (String) recipient.get("username");
            String email = (String) recipient.get("email");
            String drawingRef = (String) variables.get("drawingRef");
            String revision = (String) variables.get("revision");
            String status = (String) variables.get("status");
            Instant registeredAt = Instant.parse((String) variables.get("registeredAt"));
            String previous = variables.get("supersededRevision") instanceof String s && !s.isBlank() ? s : "none";
            List<Map<String, Object>> workPacks = (List<Map<String, Object>>) variables.get("workpacks");
            if (username == null || email == null || drawingRef == null || revision == null || status == null || workPacks == null || workPacks.isEmpty()) {
                throw new IllegalArgumentException("incomplete");
            }
            List<UUID> ids = workPacks.stream().map(w -> UUID.fromString((String) w.get("id"))).toList();
            String numbers = String.join(", ", workPacks.stream().map(w -> (String) w.get("number")).toList());

            notifications.record(NotificationRepository.IN_SYSTEM, ids, drawingRef, revision, username, clock.instant());
            if (notifications.emailRecorded(username, drawingRef, revision)) {
                LOG.info("send-revision-notice job {}: the email about {} {} to {} was already sent; nothing more to do", job.getKey(), drawingRef, revision, username);
            } else {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(from);
                message.setTo(email);
                message.setSubject("Drawing " + drawingRef + " revised to " + revision);
                message.setText(body(numbers, drawingRef, previous, revision, status, registeredAt));
                mail.send(message);
                notifications.record(NotificationRepository.EMAIL, ids, drawingRef, revision, username, clock.instant());
                LOG.info("send-revision-notice job {}: {} {} notified to {} (email and in-system notice)", job.getKey(), drawingRef, revision, username);
            }
        } catch (ClassCastException | NullPointerException | IllegalArgumentException | DateTimeParseException e) {
            LOG.warn("send-revision-notice job {} (instance {}): the job variables are not a complete notice: {}", job.getKey(), job.getProcessInstanceKey(), e.toString());
            client.newFailCommand(job.getKey()).retries(0).errorMessage("the job variables are not a complete notice").send().join();
            return;
        }
        client.newCompleteCommand(job.getKey()).send().join();
    }

    private String body(String workPacks, String drawingRef, String previous, String revision, String status, Instant registeredAt) {
        return "A new revision of a drawing on your Work Pack has been registered.\n\n"
                + "Work Pack(s): " + workPacks + "\n"
                + "Drawing: " + drawingRef + "\n"
                + "Previous revision: " + previous + "\n"
                + "New revision: " + revision + "\n"
                + "Status: " + status + "\n"
                + "Registered at: " + TIME.format(registeredAt.atZone(zone)) + " (" + zone.getId() + ")\n";
    }
}
