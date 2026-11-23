package com.lcs.star.bpa.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * cbs_block (BPA-69; E2-21; data model §13.4.3), read only: POC master data, real SOR D365 F&amp;O Cost Control Report. The one lookup of "is this CBS closed
 * for productive hours", shared by the save-time check ({@code HourLineService}) and the send-time check ({@code PrepareHoursSubmissionWorker}), so there is
 * no second copy of the rule.
 */
@Repository
public class CbsBlockRepository {

    /** The block type that closes a CBS for productive hours (FMS time sheet); Jira: "FmsTimesheet". */
    public static final String FMS_TIMESHEET = "FmsTimesheet";

    private final JdbcTemplate jdbc;

    public CbsBlockRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * True when the time sheet carries a CBS and {@code cbs_block} has {@code is_closed = true} for it with block type FmsTimesheet. No CBS (D40) or no
     * row is false: no row means open (ASSUMPTION in Jira).
     */
    public boolean closedForProductiveHours(String cbsCodeRef) {
        if (cbsCodeRef == null) {
            return false;
        }
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM bpa.cbs_block WHERE cbs_code_ref = ? AND block_type = ? AND is_closed)", Boolean.class, cbsCodeRef, FMS_TIMESHEET));
    }
}
