package com.lcs.star.bpa.progress;

import com.lcs.star.bpa.adapter.CamundaGroupClient;
import com.lcs.star.bpa.config.AlertSettings;
import com.lcs.star.bpa.persistence.NotificationRepository;
import com.lcs.star.bpa.persistence.ProgressPeriodRepository.Period;
import com.lcs.star.bpa.persistence.VarianceAlertRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * The man-hour variance alert (BPA-64; E2-14; D3; FRD-22 §22.10; ADR 0007, ADR 0012). For each CBS that came out OVER_15_PCT:
 * <ul>
 * <li>every member of the group {@code cost-controller} gets an in-system alert and an email (the address is {@code app_user.email}, D63);</li>
 * <li>the PCON Manager, who has no login (D3), gets the same email at {@code bpa.alerts.pcon-manager-email}: an email only.</li>
 * </ul>
 * Once per period, CBS, alert type, recipient and channel: the row of {@code variance_alert} is the guard (a new week is a new period, so it alerts again). The in-system
 * row is written first and kept even if the mail server is down; an email is sent only when its row is not there and is recorded after it was sent (at-least-once, as
 * BPA-16): a retry sends nothing new. A mail failure is not caught: Camunda's tries, then an incident. The cost alert is BPA-65's and is not sent here.
 */
@Service
public class VarianceAlertService {

    private static final Logger LOG = LoggerFactory.getLogger(VarianceAlertService.class);
    static final String COST_CONTROLLER = "cost-controller";

    /** A CBS found over the forecast: its actual and Current Forecast man-hours. */
    public record Over(String cbsCodeRef, BigDecimal actualManhours, BigDecimal currentForecastManhours) {
    }

    private final VarianceAlertRepository alerts;
    private final NotificationRepository notifications;
    private final CamundaGroupClient groups;
    private final JavaMailSender mail;
    private final AlertSettings settings;
    private final Clock clock;
    private final String from;

    public VarianceAlertService(VarianceAlertRepository alerts, NotificationRepository notifications, CamundaGroupClient groups, JavaMailSender mail, AlertSettings settings,
            Clock clock, @Value("${bpa.mail.from:star-bpa@example.com}") String from) {
        this.alerts = alerts;
        this.notifications = notifications;
        this.groups = groups;
        this.mail = mail;
        this.settings = settings;
        this.clock = clock;
        this.from = from;
    }

    /** Sends the alerts of the period's over-forecast CBS; {@code username} is who is recorded as sending (the service user). */
    public void sendManhourAlerts(Period period, List<Over> overs, String username) {
        if (overs.isEmpty()) {
            return;
        }
        Set<String> controllers = new LinkedHashSet<>(groups.usernames(COST_CONTROLLER));
        if (controllers.isEmpty()) {
            LOG.warn("Group {} returned no members (if it has members, bpa-service lacks GROUP READ on it)", COST_CONTROLLER);
        }
        Map<String, String> emails = notifications.emails(controllers);
        String pcon = settings.pconManagerEmail();
        // QA review of BPA-64: the alert row names a user (a foreign key to app_user), so a controller with no app_user row cannot be alerted; and a CBS that is over the
        // forecast with nobody to tell must not complete silently: "alert raised" (and the remark rule) comes from the recorded rows, so this fails the job loudly
        List<String> reachable = controllers.stream().filter(emails::containsKey).toList();
        controllers.stream().filter(c -> !emails.containsKey(c)).forEach(c -> LOG.warn("{} has no app_user row, so cannot be alerted: skipped", c));
        if (reachable.isEmpty() && pcon.isBlank()) {
            throw new IllegalStateException("No recipient for the man-hour alert: group " + COST_CONTROLLER
                    + " has no member with an app_user row and no PCON Manager address is configured (bpa.alerts.pcon-manager-email)");
        }
        for (Over over : overs) {
            BigDecimal pct = VarianceMath.variancePct(over.actualManhours(), over.currentForecastManhours());
            String text = VarianceMessages.manhour(over.cbsCodeRef(), pct, over.actualManhours(), over.currentForecastManhours(), period.weekEnding());
            String subject = VarianceMessages.subject(over.cbsCodeRef(), period.weekEnding());
            for (String controller : reachable) {
                record(period, over, pct, controller, false, VarianceAlertRepository.IN_SYSTEM, username);
                String email = emails.get(controller);
                emailOnce(period, over, pct, controller, false, email, subject, text, username);
            }
            if (pcon.isBlank()) {
                LOG.warn("No PCON Manager address configured (bpa.alerts.pcon-manager-email): the alert for {} goes to the Cost Controller only", over.cbsCodeRef());
            } else {
                emailOnce(period, over, pct, pcon, true, pcon, subject, text, username);
            }
        }
    }

    private void record(Period period, Over over, BigDecimal pct, String recipient, boolean isEmail, String channel, String username) {
        alerts.record(period.id(), over.cbsCodeRef(), VarianceAlertRepository.MANHOUR, over.actualManhours(), over.currentForecastManhours(), pct, recipient, isEmail, channel,
                username, clock.instant());
    }

    /** Sends the email unless its row is already recorded, then records it. */
    private void emailOnce(Period period, Over over, BigDecimal pct, String recipient, boolean isEmail, String address, String subject, String text, String username) {
        if (alerts.exists(period.id(), over.cbsCodeRef(), VarianceAlertRepository.MANHOUR, recipient, isEmail, VarianceAlertRepository.EMAIL)) {
            LOG.info("The email about {} for the week ending {} to {} was already sent; nothing more to do", over.cbsCodeRef(), period.weekEnding(), recipient);
            return;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(address);
        message.setSubject(subject);
        message.setText(text);
        mail.send(message);
        record(period, over, pct, recipient, isEmail, VarianceAlertRepository.EMAIL, username);
        LOG.info("Man-hour alert for {}, week ending {}: emailed to {}", over.cbsCodeRef(), period.weekEnding(), recipient);
    }
}
