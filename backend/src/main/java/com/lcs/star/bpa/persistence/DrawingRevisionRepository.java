package com.lcs.star.bpa.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The read-only copy of D365 Document Control's drawing revisions (data model 5.x, domain A; BPA-16). Changed only from DrawingRevised events: a new
 * revision is inserted, and the Superseded status is applied only to the revision the event names (D45, D53). Reference-data copies are outside
 * {@code audit_log} (data model 6.1), and no user is in context for a D365 event, so the audit columns say {@code bpa-service}.
 */
@Repository
public class DrawingRevisionRepository {

    static final String SERVICE_USER = "bpa-service";

    private final JdbcTemplate jdbc;

    public DrawingRevisionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean drawingExists(String drawingRef) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM bpa.drawing WHERE drawing_ref = ?)", Boolean.class, drawingRef));
    }

    /** True when the revision was new; false when this drawing and revision were received before (D46), in which case nothing changes. */
    public boolean insertIfNew(String drawingRef, String revision, String status, Instant registeredAt, Instant now) {
        return jdbc.update("""
                INSERT INTO bpa.drawing_revision (drawing_ref, revision, status, registered_at, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (drawing_ref, revision) DO NOTHING""", drawingRef, revision, status, Timestamp.from(registeredAt), Timestamp.from(now),
                SERVICE_USER, Timestamp.from(now), SERVICE_USER) == 1;
    }

    /** Applies the Superseded status the event reports; false when the copy has no such revision (the caller logs it, D53). */
    public boolean markSuperseded(String drawingRef, String revision, Instant now) {
        return jdbc.update("""
                UPDATE bpa.drawing_revision SET status = 'Superseded', updated_at = ?, updated_by = ?
                WHERE drawing_ref = ? AND revision = ?""", Timestamp.from(now), SERVICE_USER, drawingRef, revision) == 1;
    }
}
