package com.lcs.star.bpa.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drawing-revision notices (data model 5.9, domain E; BPA-16; D44). A notice is POC workflow output, so every row written is audited per business
 * column (data model 6.1, D64), by {@code bpa-service}: no user is in context in a job. UNIQUE(workpack_id, drawing_ref, revision,
 * recipient_username, channel) makes both writes idempotent (09 S7): a retried job writes nothing twice.
 */
@Repository
public class NotificationRepository {

    public static final String IN_SYSTEM = "InSystem";
    public static final String EMAIL = "Email";
    static final String SERVICE_USER = "bpa-service";

    /** A Work Pack that is notified: Released or In Progress and linked to the drawing (D11). */
    public record LiveWorkPack(UUID id, String number) {
    }

    /** One in-system notice as the user sees it. */
    public record Notice(String workpackNumber, String drawingRef, String revision, Instant sentAt) {
    }

    private final JdbcTemplate jdbc;

    public NotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The Work Packs on this drawing that are Released or In Progress, by Work Pack number. */
    public List<LiveWorkPack> liveWorkPacksOn(String drawingRef) {
        return jdbc.query("""
                SELECT id, workpack_number FROM bpa.workpack
                WHERE drawing_ref = ? AND status IN ('Released', 'In Progress') ORDER BY workpack_number""",
                (rs, n) -> new LiveWorkPack(rs.getObject("id", UUID.class), rs.getString("workpack_number")), drawingRef);
    }

    /** The email of each username that has an app_user row (D63); a user with none is left out. */
    public Map<String, String> emails(Collection<String> usernames) {
        Map<String, String> found = new LinkedHashMap<>();
        for (String username : usernames) {
            jdbc.query("SELECT email FROM bpa.app_user WHERE username = ?", rs -> {
                found.put(username, rs.getString("email"));
            }, username);
        }
        return found;
    }

    /** True when an email for this recipient and revision was already recorded: "any existing row means the email went" (data model 5.9). */
    public boolean emailRecorded(String recipientUsername, String drawingRef, String revision) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM bpa.notification
                               WHERE recipient_username = ? AND drawing_ref = ? AND revision = ? AND channel = 'Email')""", Boolean.class,
                recipientUsername, drawingRef, revision));
    }

    /** One row per Work Pack, all in one transaction (a retry never records half an email); rows that already exist are left alone. */
    @Transactional
    public int record(String channel, List<UUID> workpackIds, String drawingRef, String revision, String recipientUsername, Instant now) {
        int written = 0;
        for (UUID workpackId : workpackIds) {
            List<UUID> inserted = jdbc.queryForList("""
                    INSERT INTO bpa.notification (workpack_id, drawing_ref, revision, recipient_username, channel, sent_at,
                                                  created_at, created_by, updated_at, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (workpack_id, drawing_ref, revision, recipient_username, channel) DO NOTHING
                    RETURNING id""", UUID.class, workpackId, drawingRef, revision, recipientUsername, channel, Timestamp.from(now), Timestamp.from(now),
                    SERVICE_USER, Timestamp.from(now), SERVICE_USER);
            if (!inserted.isEmpty()) {
                UUID id = inserted.get(0);
                audit(id, "workpack_id", workpackId.toString(), now);
                audit(id, "drawing_ref", drawingRef, now);
                audit(id, "revision", revision, now);
                audit(id, "recipient_username", recipientUsername, now);
                audit(id, "channel", channel, now);
                audit(id, "sent_at", now.toString(), now);
                written++;
            }
        }
        return written;
    }

    /** The user's in-system notices, newest first. Only the session user's own: no argument other than the name of the logged-in user (09 S6). */
    public List<Notice> noticesOf(String username) {
        return jdbc.query("""
                SELECT w.workpack_number, n.drawing_ref, n.revision, n.sent_at
                FROM bpa.notification n JOIN bpa.workpack w ON w.id = n.workpack_id
                WHERE n.recipient_username = ? AND n.channel = 'InSystem'
                ORDER BY n.sent_at DESC, w.workpack_number, n.revision, n.id""",
                (rs, n) -> new Notice(rs.getString("workpack_number"), rs.getString("drawing_ref"), rs.getString("revision"),
                        rs.getTimestamp("sent_at").toInstant()), username);
    }

    private void audit(UUID recordId, String column, String newValue, Instant at) {
        jdbc.update("""
                INSERT INTO bpa.audit_log (table_name, record_id, column_name, old_value, new_value, username, changed_at)
                VALUES ('notification', ?, ?, NULL, ?, ?, ?)""", recordId, column, newValue, SERVICE_USER, Timestamp.from(at));
    }
}
