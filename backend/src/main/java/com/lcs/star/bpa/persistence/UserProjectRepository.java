package com.lcs.star.bpa.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * user_project (BPA-76; data model section 13.7): which projects a portal user has, written by the PPC Team through the portal (and, at start-up, by the S22
 * persona sync while a user has no row at all, ADR 0005). A write is user-initiated, so every row added or removed is audited per business column
 * ({@code username}, {@code project_ref}), by the session user (D64, data model section 6.1): old value null on an assignment, new value null on an
 * unassignment. The caller passes the user and the instant, as for the other user-initiated writes (CbsReviewRepository).
 */
@Repository
public class UserProjectRepository {

    private final JdbcTemplate jdbc;

    public UserProjectRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** COMPILE STUB (BPA-77, red tests first): true when a row (username, projectRef) exists. */
    public boolean exists(String username, String projectRef) {
        throw new UnsupportedOperationException("BPA-77 stub");
    }

    /** The project references of the user, by reference (ASSUMPTION: no source orders them). */
    public List<String> projectsOf(String username) {
        return jdbc.queryForList("SELECT project_ref FROM bpa.user_project WHERE username = ? ORDER BY project_ref", String.class, username);
    }

    /**
     * Gives the user the project. False (and nothing written) when the user already has it: UNIQUE (username, project_ref) is the rule, so two clicks at the same
     * moment cannot both succeed.
     */
    @Transactional
    public boolean assign(String username, String projectRef, String by, Instant now) {
        Timestamp at = Timestamp.from(now);
        List<UUID> inserted = jdbc.queryForList("""
                INSERT INTO bpa.user_project (username, project_ref, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (username, project_ref) DO NOTHING
                RETURNING id""", UUID.class, username, projectRef, at, by, at, by);
        if (inserted.isEmpty()) {
            return false;
        }
        audit(inserted.get(0), "username", null, username, by, at);
        audit(inserted.get(0), "project_ref", null, projectRef, by, at);
        return true;
    }

    /** Takes the project from the user. False (and nothing written) when the user did not have it. */
    @Transactional
    public boolean unassign(String username, String projectRef, String by, Instant now) {
        Timestamp at = Timestamp.from(now);
        List<UUID> deleted = jdbc.queryForList("DELETE FROM bpa.user_project WHERE username = ? AND project_ref = ? RETURNING id", UUID.class, username, projectRef);
        if (deleted.isEmpty()) {
            return false;
        }
        audit(deleted.get(0), "username", username, null, by, at);
        audit(deleted.get(0), "project_ref", projectRef, null, by, at);
        return true;
    }

    private void audit(UUID recordId, String column, String oldValue, String newValue, String username, Timestamp at) {
        jdbc.update("""
                INSERT INTO bpa.audit_log (table_name, record_id, column_name, old_value, new_value, username, changed_at)
                VALUES ('user_project', ?, ?, ?, ?, ?, ?)""", recordId, column, oldValue, newValue, username, at);
    }
}
