package com.lcs.star.bpa.persistence;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * variance_alert (BPA-64, BPA-65; data model §13.5): the man-hour and cost alerts sent. A row is one recipient on one channel: a user ({@code recipient_username}) or,
 * for the PCON Manager who has no login, an address ({@code recipient_email}, V5). Two partial unique indexes are the once-only guards (ADR 0007, V6):
 * <ul>
 * <li>Manhour: once per (period, CBS, whichever recipient is set, channel), a new week alerts again (BPA-64);</li>
 * <li>Cost: once per (CBS, whichever recipient is set, channel, {@code alert_date}), whether the weekly or the daily close check sends it (BPA-65); a daily alert has no
 * period, and a weekly check the same day links it to its week ({@link #linkCostToPeriod}).</li>
 * </ul>
 * An insert that finds its row does nothing. One {@code audit_log} row per business column of an inserted row (D64).
 */
@Repository
public class VarianceAlertRepository {

    public static final String MANHOUR = "Manhour";
    public static final String COST = "Cost";
    public static final String IN_SYSTEM = "InSystem";
    public static final String EMAIL = "Email";

    private final JdbcTemplate jdbc;
    private final ZoneId zone;

    public VarianceAlertRepository(JdbcTemplate jdbc, ZoneId businessZone) {
        this.jdbc = jdbc;
        this.zone = businessZone;
    }

    /** One in-system alert of a user: the week it belongs to (null for a daily close cost alert no weekly check linked), its date and the values its text is built from. */
    public record Mine(LocalDate weekEnding, LocalDate alertDate, String cbsCodeRef, String alertType, BigDecimal actualValue, BigDecimal baselineValue, BigDecimal variancePct,
            Instant sentAt) {
    }

    /** True when this alert to this recipient (a username, or an address when {@code recipientIsEmail}) on this channel is recorded for the period and CBS. */
    public boolean exists(UUID periodId, String cbs, String type, String recipient, boolean recipientIsEmail, String channel) {
        return jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM bpa.variance_alert WHERE progress_period_id = ? AND cbs_code_ref = ? AND alert_type = ? AND channel = ?
                               AND coalesce(recipient_username, recipient_email) = ?)""", Boolean.class, periodId, cbs, type, channel, recipient);
    }

    /** True when the Cost alert to this recipient on this channel is recorded for the CBS and the day (BPA-65). */
    public boolean existsCost(String cbs, String recipient, String channel, LocalDate alertDate) {
        return jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM bpa.variance_alert WHERE alert_type = 'Cost' AND cbs_code_ref = ? AND channel = ? AND alert_date = ?
                               AND coalesce(recipient_username, recipient_email) = ?)""", Boolean.class, cbs, channel, Date.valueOf(alertDate), recipient);
    }

    /**
     * BPA-66 (decision (c): once per week per CBS): true when a Cost alert for the CBS, recipient and channel is already linked to this period, whatever day it was sent, so a
     * send-back re-run of the weekly check on a later day does not send it again. The daily close check (no period) keeps BPA-65's once per day.
     */
    public boolean existsCostInPeriod(UUID periodId, String cbs, String recipient, String channel) {
        return jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM bpa.variance_alert WHERE alert_type = 'Cost' AND progress_period_id = ? AND cbs_code_ref = ? AND channel = ?
                               AND coalesce(recipient_username, recipient_email) = ?)""", Boolean.class, periodId, cbs, channel, recipient);
    }

    /** Records the Manhour alert; returns false (and writes nothing) when this recipient already has it on this channel for the period and CBS. */
    public boolean record(UUID periodId, String cbs, String type, BigDecimal actual, BigDecimal baseline, BigDecimal pct, String recipient, boolean recipientIsEmail,
            String channel, String username, Instant sentAt) {
        Timestamp at = Timestamp.from(sentAt.truncatedTo(ChronoUnit.MICROS));
        LocalDate alertDate = LocalDate.ofInstant(sentAt, zone);
        Optional<UUID> id = jdbc.queryForList("""
                INSERT INTO bpa.variance_alert (progress_period_id, cbs_code_ref, alert_type, actual_value, baseline_value, variance_pct, recipient_username, recipient_email,
                                                channel, sent_at, alert_date, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (progress_period_id, cbs_code_ref, alert_type, (coalesce(recipient_username, recipient_email)), channel) WHERE alert_type = 'Manhour' DO NOTHING
                RETURNING id""", UUID.class, periodId, cbs, type, actual, baseline, pct, recipientIsEmail ? null : recipient, recipientIsEmail ? recipient : null, channel, at,
                Date.valueOf(alertDate), at, username, at, username).stream().findFirst();
        if (id.isEmpty()) {
            return false;
        }
        auditInsert(id.get(), recipientIsEmail, actual, baseline, pct, recipient, channel, username, at);
        return true;
    }

    /**
     * Records the Cost alert (BPA-65) for the CBS and the day; {@code periodId} is null for a daily close alert. Returns false (and writes nothing) when this recipient
     * already has it on this channel for the CBS and the day, whichever check sent it.
     */
    public boolean recordCost(UUID periodId, LocalDate alertDate, String cbs, BigDecimal actual, BigDecimal baseline, BigDecimal pct, String recipient, boolean recipientIsEmail,
            String channel, String username, Instant sentAt) {
        Timestamp at = Timestamp.from(sentAt.truncatedTo(ChronoUnit.MICROS));
        Optional<UUID> id = jdbc.queryForList("""
                INSERT INTO bpa.variance_alert (progress_period_id, cbs_code_ref, alert_type, actual_value, baseline_value, variance_pct, recipient_username, recipient_email,
                                                channel, sent_at, alert_date, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, 'Cost', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (cbs_code_ref, (coalesce(recipient_username, recipient_email)), channel, alert_date) WHERE alert_type = 'Cost' DO NOTHING
                RETURNING id""", UUID.class, periodId, cbs, actual, baseline, pct, recipientIsEmail ? null : recipient, recipientIsEmail ? recipient : null, channel, at,
                Date.valueOf(alertDate), at, username, at, username).stream().findFirst();
        if (id.isEmpty()) {
            return false;
        }
        auditInsert(id.get(), recipientIsEmail, actual, baseline, pct, recipient, channel, username, at);
        if (periodId != null) {
            audit(id.get(), "progress_period_id", null, periodId.toString(), username, at);
        }
        audit(id.get(), "alert_date", null, alertDate.toString(), username, at);
        return true;
    }

    /**
     * The weekly check found the CBS's Cost alerts of the day already sent by the daily close check (no period): they are linked to the week, so the week's review shows
     * the alert and needs the remark (BPA-65, plan Q3). Rows already linked to a week are left alone. One audit row per linked row.
     */
    public void linkCostToPeriod(UUID periodId, LocalDate alertDate, String cbs, String username, Instant now) {
        Timestamp at = Timestamp.from(now.truncatedTo(ChronoUnit.MICROS));
        List<UUID> linked = jdbc.queryForList("""
                UPDATE bpa.variance_alert SET progress_period_id = ?, updated_at = ?, updated_by = ?
                WHERE alert_type = 'Cost' AND cbs_code_ref = ? AND alert_date = ? AND progress_period_id IS NULL
                RETURNING id""", UUID.class, periodId, at, username, cbs, Date.valueOf(alertDate));
        linked.forEach(id -> audit(id, "progress_period_id", null, periodId.toString(), username, at));
    }

    /** The CBS that have an alert of the type recorded for the period (any recipient). */
    public Set<String> alertedCbs(UUID periodId, String type) {
        return new HashSet<>(jdbc.queryForList("SELECT DISTINCT cbs_code_ref FROM bpa.variance_alert WHERE progress_period_id = ? AND alert_type = ?", String.class, periodId, type));
    }

    /** The user's in-system alerts, newest first. */
    public List<Mine> inSystemOf(String username) {
        return jdbc.query("""
                SELECT d.week_ending, a.alert_date, a.cbs_code_ref, a.alert_type, a.actual_value, a.baseline_value, a.variance_pct, a.sent_at
                FROM bpa.variance_alert a LEFT JOIN bpa.progress_period d ON d.id = a.progress_period_id
                WHERE a.recipient_username = ? AND a.channel = 'InSystem' ORDER BY a.sent_at DESC, a.cbs_code_ref""",
                (rs, n) -> new Mine(rs.getDate("week_ending") == null ? null : ((Date) rs.getDate("week_ending")).toLocalDate(), ((Date) rs.getDate("alert_date")).toLocalDate(),
                        rs.getString("cbs_code_ref"), rs.getString("alert_type"), rs.getBigDecimal("actual_value"), rs.getBigDecimal("baseline_value"),
                        rs.getBigDecimal("variance_pct"), rs.getTimestamp("sent_at").toInstant()), username);
    }

    private void auditInsert(UUID id, boolean recipientIsEmail, BigDecimal actual, BigDecimal baseline, BigDecimal pct, String recipient, String channel, String username,
            Timestamp at) {
        audit(id, "actual_value", null, actual.toPlainString(), username, at);
        audit(id, "baseline_value", null, baseline.toPlainString(), username, at);
        audit(id, "variance_pct", null, pct.toPlainString(), username, at);
        audit(id, recipientIsEmail ? "recipient_email" : "recipient_username", null, recipient, username, at);
        audit(id, "channel", null, channel, username, at);
    }

    private void audit(UUID recordId, String column, String oldValue, String newValue, String username, Timestamp at) {
        jdbc.update("""
                INSERT INTO bpa.audit_log (table_name, record_id, column_name, old_value, new_value, username, changed_at)
                VALUES ('variance_alert', ?, ?, ?, ?, ?, ?)""", recordId, column, oldValue, newValue, username, at);
    }
}
