package com.lcs.star.bpa.persistence;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * variance_alert (BPA-64; data model §13.5): the man-hour (and, BPA-65, cost) alerts sent for a period and CBS. A row is one recipient on one channel: a user
 * ({@code recipient_username}) or, for the PCON Manager who has no login, an address ({@code recipient_email}, V5). The unique index over (period, CBS, type,
 * whichever recipient is set, channel) is the once-only guard of {@code send-variance-alerts} (ADR 0007); an insert that finds its row does nothing. One
 * {@code audit_log} row per business column of an inserted row (D64).
 */
@Repository
public class VarianceAlertRepository {

    public static final String MANHOUR = "Manhour";
    public static final String IN_SYSTEM = "InSystem";
    public static final String EMAIL = "Email";

    private final JdbcTemplate jdbc;

    public VarianceAlertRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One in-system alert of a user, with the week it is for and the values its text is built from. */
    public record Mine(LocalDate weekEnding, String cbsCodeRef, String alertType, BigDecimal actualValue, BigDecimal baselineValue, BigDecimal variancePct, Instant sentAt) {
    }

    /** True when this alert to this recipient (a username, or an address when {@code recipientIsEmail}) on this channel is recorded for the period and CBS. */
    public boolean exists(UUID periodId, String cbs, String type, String recipient, boolean recipientIsEmail, String channel) {
        return jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM bpa.variance_alert WHERE progress_period_id = ? AND cbs_code_ref = ? AND alert_type = ? AND channel = ?
                               AND coalesce(recipient_username, recipient_email) = ?)""", Boolean.class, periodId, cbs, type, channel, recipient);
    }

    /** Records the alert; returns false (and writes nothing) when this recipient already has it on this channel for the period and CBS. */
    public boolean record(UUID periodId, String cbs, String type, BigDecimal actual, BigDecimal baseline, BigDecimal pct, String recipient, boolean recipientIsEmail,
            String channel, String username, Instant sentAt) {
        Timestamp at = Timestamp.from(sentAt.truncatedTo(ChronoUnit.MICROS));
        Optional<UUID> id = jdbc.queryForList("""
                INSERT INTO bpa.variance_alert (progress_period_id, cbs_code_ref, alert_type, actual_value, baseline_value, variance_pct, recipient_username, recipient_email,
                                                channel, sent_at, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (progress_period_id, cbs_code_ref, alert_type, (coalesce(recipient_username, recipient_email)), channel) DO NOTHING
                RETURNING id""", UUID.class, periodId, cbs, type, actual, baseline, pct, recipientIsEmail ? null : recipient, recipientIsEmail ? recipient : null, channel, at, at,
                username, at, username).stream().findFirst();
        if (id.isEmpty()) {
            return false;
        }
        audit(id.get(), "actual_value", actual.toPlainString(), username, at);
        audit(id.get(), "baseline_value", baseline.toPlainString(), username, at);
        audit(id.get(), "variance_pct", pct.toPlainString(), username, at);
        audit(id.get(), recipientIsEmail ? "recipient_email" : "recipient_username", recipient, username, at);
        audit(id.get(), "channel", channel, username, at);
        return true;
    }

    /** The CBS that have a Manhour alert recorded for the period (any recipient). */
    public Set<String> alertedCbs(UUID periodId, String type) {
        return new HashSet<>(jdbc.queryForList("SELECT DISTINCT cbs_code_ref FROM bpa.variance_alert WHERE progress_period_id = ? AND alert_type = ?", String.class, periodId, type));
    }

    /** The user's in-system alerts, newest first. */
    public List<Mine> inSystemOf(String username) {
        return jdbc.query("""
                SELECT d.week_ending, a.cbs_code_ref, a.alert_type, a.actual_value, a.baseline_value, a.variance_pct, a.sent_at
                FROM bpa.variance_alert a JOIN bpa.progress_period d ON d.id = a.progress_period_id
                WHERE a.recipient_username = ? AND a.channel = 'InSystem' ORDER BY a.sent_at DESC, a.cbs_code_ref""",
                (rs, n) -> new Mine(((Date) rs.getDate("week_ending")).toLocalDate(), rs.getString("cbs_code_ref"), rs.getString("alert_type"), rs.getBigDecimal("actual_value"),
                        rs.getBigDecimal("baseline_value"), rs.getBigDecimal("variance_pct"), rs.getTimestamp("sent_at").toInstant()), username);
    }

    private void audit(UUID recordId, String column, String newValue, String username, Timestamp at) {
        jdbc.update("""
                INSERT INTO bpa.audit_log (table_name, record_id, column_name, old_value, new_value, username, changed_at)
                VALUES ('variance_alert', ?, ?, NULL, ?, ?, ?)""", recordId, column, newValue, username, at);
    }
}
