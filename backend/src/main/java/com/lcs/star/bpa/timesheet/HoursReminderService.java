package com.lcs.star.bpa.timesheet;

import com.lcs.star.bpa.adapter.CamundaGroupClient;
import com.lcs.star.bpa.persistence.HoursSubmissionRepository;
import com.lcs.star.bpa.persistence.HoursSubmissionRepository.Reached;
import com.lcs.star.bpa.persistence.NotificationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * The reminder behind {@code send-hours-reminder} (BPA-74; E2-24; FRD-5-12 section 5.12.10). The model's non-interrupting 24 h timer on each approval task fires
 * this every day the task is open; the 48 hour rule is here. If the open submission of the process instance at this step reached it at least 48 hours ago
 * (the injected clock; {@link HoursSubmissionRepository#reachedStep}), every user of the recipient group gets an in-system notice and an email: "Hours for
 * <workpackNumber> are waiting for your decision since <d MMM yyyy>", the date of Asia/Riyadh (ADR 0013). Otherwise nothing happens, and nothing happens
 * either when no submission of the instance is waiting at the step any more (decided, returned, rejected).
 *
 * <p>Idempotent on the timer firing (plan BPA-74): the reminder key is the process instance key, the step and the firing's element instance key, so a retried
 * job writes and sends nothing twice, and the next day's firing, a new element instance, sends again. As for the drawing notices (BPA-16), the in-system
 * row is committed first, a mail failure is not caught (Camunda's three tries, then an incident, S7, ADR 0007) and "an Email row for this key and recipient
 * means the email went" (at-least-once if the process stops between the send and the record: ASSUMPTION). A member of the group with no {@code app_user}
 * row has no address and cannot be a notice's recipient (the foreign key), so is left out and logged. The subject and the plain-text body of the email are
 * ASSUMPTIONs (no source gives them): the subject is "Hours for <workpackNumber> are waiting for your decision", the body is the same sentence as the notice.
 */
@Service
public class HoursReminderService {

    static final Duration WAIT = Duration.ofHours(48);
    private static final Logger LOG = LoggerFactory.getLogger(HoursReminderService.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final HoursSubmissionRepository submissions;
    private final NotificationRepository notifications;
    private final CamundaGroupClient groups;
    private final JavaMailSender mail;
    private final Clock clock;
    private final ZoneId businessZone;
    private final String from;

    public HoursReminderService(HoursSubmissionRepository submissions, NotificationRepository notifications, CamundaGroupClient groups, JavaMailSender mail,
            Clock clock, ZoneId businessZone, @Value("${bpa.mail.from:star-bpa@example.com}") String from) {
        this.submissions = submissions;
        this.notifications = notifications;
        this.groups = groups;
        this.mail = mail;
        this.clock = clock;
        this.businessZone = businessZone;
        this.from = from;
    }

    /** One firing of the timer. Returns how many users were reminded (0 when it is too early or the hours are not at the step). */
    public int remind(String step, String recipientGroup, long processInstanceKey, long elementInstanceKey, String workpackNumber) {
        Optional<Reached> reached = submissions.reachedStep(processInstanceKey, step);
        if (reached.isEmpty() || Duration.between(reached.get().at(), clock.instant()).compareTo(WAIT) < 0) {
            return 0;
        }
        String message = "Hours for " + workpackNumber + " are waiting for your decision since " + DATE.format(reached.get().at().atZone(businessZone));
        String key = processInstanceKey + ":" + step + ":" + elementInstanceKey;
        List<String> usernames = groups.usernames(recipientGroup);
        Map<String, String> emails = notifications.emails(usernames);
        int reminded = 0;
        for (String username : usernames) {
            String email = emails.get(username);
            if (email == null) {
                LOG.warn("send-hours-reminder: {} of group {} has no app_user row, so no notice or email can be recorded for them", username, recipientGroup);
                continue;
            }
            notifications.recordReminder(NotificationRepository.IN_SYSTEM, reached.get().workpackId(), username, message, key, clock.instant());
            if (!notifications.reminderEmailRecorded(username, key)) {
                SimpleMailMessage mailMessage = new SimpleMailMessage();
                mailMessage.setFrom(from);
                mailMessage.setTo(email);
                mailMessage.setSubject("Hours for " + workpackNumber + " are waiting for your decision");
                mailMessage.setText(message);
                mail.send(mailMessage);
                notifications.recordReminder(NotificationRepository.EMAIL, reached.get().workpackId(), username, message, key, clock.instant());
            }
            reminded++;
        }
        return reminded;
    }
}
