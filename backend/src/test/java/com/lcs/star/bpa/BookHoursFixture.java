package com.lcs.star.bpa;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The Background of BPA-50, made with SQL instead of by running Epic 1 (fast, and the Gherkin numbers can be used as written): WP-000457 is
 * Released with phases Cutting 300 h and Welding 200 h; E1021 and E1099 are assigned to it, each with a Draft time sheet and no lines; E1150
 * has a time sheet but no assignment (the "Worker must be assigned" scenario, plan Q6); and the week ending 12 Nov 2026 is cut off
 * (a progress_period row; plan Q4). Workers come from the seed.
 */
final class BookHoursFixture {

    static final String WORKPACK = "WP-000457";

    record Ids(UUID workpackId, Map<String, UUID> timesheets) {
        UUID timesheet(String badge) {
            return timesheets.get(badge);
        }
    }

    private final JdbcTemplate jdbc;

    BookHoursFixture(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Removes what an earlier test of this class left, then builds the Background. */
    Ids reset() {
        // BPA-52: a logged row points at its line, a line at its upload file and at its submission, so: rows, decisions, lines, files, submissions (BPA-53: a decision points at its submission)
        // BPA-58: a line points at its FMS file (cleared right after the lines) and a file at its progress period
        for (String table : new String[] {"timesheet_file_row", "hours_decision", "digital_timesheet_line", "fms_hours_export", "completion_report_row", "completion_report", "progress_retraction", "workpack_progress",
                "timesheet_file", "hours_submission", "digital_timesheet", "hour_journal_line", "hour_journal_header", "worker_assignment", "assignment_file", "workpack_phase", "workpack",
                "activity_progress", "progress_period", "audit_log"}) {
            jdbc.update("DELETE FROM bpa." + table);
        }
        UUID workpack = jdbc.queryForObject("""
                INSERT INTO bpa.workpack (workpack_number, activity_ref, drawing_ref, department_ref, completion_date, status, assignment_state,
                                          process_instance_key, created_by, updated_by)
                VALUES (?, 'A-2201', 'DWG-118', 'Fabrication', DATE '2026-11-30', 'Released', 'Assigned', 9000000001, 'test', 'test')
                RETURNING id""", UUID.class, WORKPACK);
        jdbc.update("INSERT INTO bpa.workpack_phase (workpack_id, phase, manhours, created_by, updated_by) VALUES (?, 'Cutting', 300, 'test', 'test')", workpack);
        jdbc.update("INSERT INTO bpa.workpack_phase (workpack_id, phase, manhours, created_by, updated_by) VALUES (?, 'Welding', 200, 'test', 'test')", workpack);
        UUID header = jdbc.queryForObject("""
                INSERT INTO bpa.hour_journal_header (workpack_id, estimated_manhours, created_by, updated_by) VALUES (?, 500, 'test', 'test') RETURNING id""",
                UUID.class, workpack);
        UUID file = jdbc.queryForObject("""
                INSERT INTO bpa.assignment_file (file_name, sha256, uploaded_by, received_at, created_by, updated_by)
                VALUES ('background.xlsx', 'background', 'test', now(), 'test', 'test') RETURNING id""", UUID.class);
        Map<String, UUID> timesheets = new LinkedHashMap<>();
        for (String badge : new String[] {"E1021", "E1099", "E1150"}) {
            UUID worker = jdbc.queryForObject("SELECT id FROM bpa.worker WHERE employee_ref = ?", UUID.class, badge);
            UUID journalLine = jdbc.queryForObject("""
                    INSERT INTO bpa.hour_journal_line (header_id, worker_id, role, designation, estimated_hours, created_by, updated_by)
                    SELECT ?, w.id, w.role, w.designation, 100, 'test', 'test' FROM bpa.worker w WHERE w.id = ? RETURNING id""", UUID.class, header, worker);
            if (!badge.equals("E1150")) {
                jdbc.update("""
                        INSERT INTO bpa.worker_assignment (workpack_id, worker_id, estimated_hours, assignment_file_id, created_by, updated_by)
                        VALUES (?, ?, 100, ?, 'test', 'test')""", workpack, worker, file);
            }
            timesheets.put(badge, jdbc.queryForObject("""
                    INSERT INTO bpa.digital_timesheet (worker_id, workpack_id, journal_line_id, project_ref, wbs_ref, activity_ref, created_by, updated_by)
                    VALUES (?, ?, ?, 'PRJ-139', '11031', 'A-2201', 'test', 'test') RETURNING id""", UUID.class, worker, workpack, journalLine));
        }
        // Week ending Thursday 12 Nov 2026 is cut off (E2-D15; a row exists once the weekly process has run)
        jdbc.update("""
                INSERT INTO bpa.progress_period (week_ending, cut_off_at, status, process_instance_key, created_by, updated_by)
                VALUES (DATE '2026-11-12', TIMESTAMPTZ '2026-11-12 20:59:00+00', 'CutOff', 9000000002, 'test', 'test')""");
        return new Ids(workpack, timesheets);
    }

    /** The time sheets BPA-58's Background names beyond {@link #reset()}'s: E1021, E1099 and E2001 on WP-000457, E1150 on WP-000470. */
    record FmsIds(UUID e1021, UUID e1099, UUID e2001, UUID e1150OnWp470) {
    }

    /**
     * The Background of BPA-58 (E2-08), made with SQL on top of {@link #reset()}: CBS-139-PIP-01 on the time sheets of E1021 and E1099 (WP-000457, activity A-2201);
     * E2001, the agency Worker (seed), with a time sheet on WP-000457; WP-000470 (activity A-2299, department Fabrication, which has no CBS in the seed) with
     * a time sheet for E1150, the Worker with no cost centre. Lines are inserted by SQL with the stated status, which checks no assignment.
     */
    FmsIds fmsBackground(Ids ids) {
        jdbc.update("UPDATE bpa.digital_timesheet SET cbs_code_ref = 'CBS-139-PIP-01' WHERE id IN (?, ?)", ids.timesheet("E1021"), ids.timesheet("E1099"));
        UUID header = jdbc.queryForObject("SELECT id FROM bpa.hour_journal_header WHERE workpack_id = ?", UUID.class, ids.workpackId());
        UUID e2001 = timesheetOn(header, ids.workpackId(), "E2001", "A-2201");
        UUID workpack470 = jdbc.queryForObject("""
                INSERT INTO bpa.workpack (workpack_number, activity_ref, drawing_ref, department_ref, completion_date, status, assignment_state,
                                          process_instance_key, created_by, updated_by)
                VALUES ('WP-000470', 'A-2299', 'DWG-118', 'Fabrication', DATE '2026-11-30', 'In Progress', 'Assigned', 9000000005, 'test', 'test')
                RETURNING id""", UUID.class);
        jdbc.update("INSERT INTO bpa.workpack_phase (workpack_id, phase, manhours, created_by, updated_by) VALUES (?, 'Welding', 100, 'test', 'test')", workpack470);
        UUID header470 = jdbc.queryForObject("""
                INSERT INTO bpa.hour_journal_header (workpack_id, estimated_manhours, created_by, updated_by) VALUES (?, 100, 'test', 'test') RETURNING id""",
                UUID.class, workpack470);
        UUID e1150 = timesheetOn(header470, workpack470, "E1150", "A-2299");
        return new FmsIds(ids.timesheet("E1021"), ids.timesheet("E1099"), e2001, e1150);
    }

    private UUID timesheetOn(UUID header, UUID workpack, String badge, String activityRef) {
        UUID worker = jdbc.queryForObject("SELECT id FROM bpa.worker WHERE employee_ref = ?", UUID.class, badge);
        UUID journalLine = jdbc.queryForObject("""
                INSERT INTO bpa.hour_journal_line (header_id, worker_id, role, designation, estimated_hours, created_by, updated_by)
                SELECT ?, w.id, w.role, w.designation, 100, 'test', 'test' FROM bpa.worker w WHERE w.id = ? RETURNING id""", UUID.class, header, worker);
        return jdbc.queryForObject("""
                INSERT INTO bpa.digital_timesheet (worker_id, workpack_id, journal_line_id, project_ref, wbs_ref, activity_ref, created_by, updated_by)
                VALUES (?, ?, ?, 'PRJ-139', '11031', ?, 'test', 'test') RETURNING id""", UUID.class, worker, workpack, journalLine, activityRef);
    }

    /**
     * The Background of BPA-59 (E2-09), made with SQL on top of {@link #reset()}: WP-000457 and WP-000461 are In Progress (the Work Packs the story's
     * report names) and WP-000480 is Released with no hours. A report row is checked against the Work Pack's number and status only.
     */
    void completionBackground() {
        workPack461();
        jdbc.update("UPDATE bpa.workpack SET status = 'In Progress' WHERE workpack_number IN ('WP-000457', 'WP-000461')");
        jdbc.update("""
                INSERT INTO bpa.workpack (workpack_number, activity_ref, drawing_ref, department_ref, completion_date, status, assignment_state,
                                          process_instance_key, created_by, updated_by)
                VALUES ('WP-000480', 'A-2201', 'DWG-118', 'Fabrication', DATE '2026-11-30', 'Released', 'Assigned', 9000000006, 'test', 'test')""");
    }

    /** The five lines of BPA-58's Background, with the statuses it states. */
    void fmsLines(FmsIds f) {
        line(f.e1021(), LocalDate.of(2026, 11, 16), "Cutting", new BigDecimal("8"), "Approved");
        line(f.e1099(), LocalDate.of(2026, 11, 16), "Cutting", new BigDecimal("7.5"), "Approved");
        line(f.e1021(), LocalDate.of(2026, 11, 17), "Cutting", new BigDecimal("8"), "Submitted");
        line(f.e1099(), LocalDate.of(2026, 11, 17), "Welding", new BigDecimal("6"), "Rejected");
        line(f.e1150OnWp470(), LocalDate.of(2026, 11, 17), "Welding", new BigDecimal("4"), "Approved");
    }

    /** A line already on the time sheet, as a Given states it. */
    UUID line(UUID timesheet, LocalDate date, String phase, BigDecimal hours, String status) {
        return jdbc.queryForObject("""
                INSERT INTO bpa.digital_timesheet_line (digital_timesheet_id, work_date, phase, hours, status, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'test', 'test') RETURNING id""", UUID.class, timesheet, java.sql.Date.valueOf(date), phase, hours, status);
    }

    /**
     * Lines that add up to {@code total} hours on the phase, as a Given states "has N hours" (BPA-51): 16 h a day on consecutive days from
     * 1 Oct 2026, the last one with the rest, so no day of them reaches the daily limit and none falls on 17 Nov.
     */
    void fill(UUID timesheet, String phase, BigDecimal total, String status) {
        BigDecimal left = total;
        LocalDate day = LocalDate.of(2026, 10, 1);
        BigDecimal day16 = new BigDecimal("16");
        while (left.signum() > 0) {
            BigDecimal hours = left.min(day16);
            line(timesheet, day, phase, hours, status);
            left = left.subtract(hours);
            day = day.plusDays(1);
        }
    }

    /**
     * WP-000461 of the BPA-52 Background: Cutting 100 h and E1021 assigned, with a Draft time sheet and no lines; the time sheet's id comes back.
     */
    UUID workPack461() {
        UUID workpack = jdbc.queryForObject("""
                INSERT INTO bpa.workpack (workpack_number, activity_ref, drawing_ref, department_ref, completion_date, status, assignment_state,
                                          process_instance_key, created_by, updated_by)
                VALUES ('WP-000461', 'A-2201', 'DWG-118', 'Fabrication', DATE '2026-11-30', 'Released', 'Assigned', 9000000004, 'test', 'test')
                RETURNING id""", UUID.class);
        jdbc.update("INSERT INTO bpa.workpack_phase (workpack_id, phase, manhours, created_by, updated_by) VALUES (?, 'Cutting', 100, 'test', 'test')", workpack);
        UUID header = jdbc.queryForObject("""
                INSERT INTO bpa.hour_journal_header (workpack_id, estimated_manhours, created_by, updated_by) VALUES (?, 100, 'test', 'test') RETURNING id""",
                UUID.class, workpack);
        UUID file = jdbc.queryForObject("SELECT id FROM bpa.assignment_file LIMIT 1", UUID.class);
        UUID worker = jdbc.queryForObject("SELECT id FROM bpa.worker WHERE employee_ref = 'E1021'", UUID.class);
        UUID journalLine = jdbc.queryForObject("""
                INSERT INTO bpa.hour_journal_line (header_id, worker_id, role, designation, estimated_hours, created_by, updated_by)
                SELECT ?, w.id, w.role, w.designation, 100, 'test', 'test' FROM bpa.worker w WHERE w.id = ? RETURNING id""", UUID.class, header, worker);
        jdbc.update("""
                INSERT INTO bpa.worker_assignment (workpack_id, worker_id, estimated_hours, assignment_file_id, created_by, updated_by)
                VALUES (?, ?, 100, ?, 'test', 'test')""", workpack, worker, file);
        return jdbc.queryForObject("""
                INSERT INTO bpa.digital_timesheet (worker_id, workpack_id, journal_line_id, project_ref, wbs_ref, activity_ref, created_by, updated_by)
                VALUES (?, ?, ?, 'PRJ-139', '11031', 'A-2201', 'test', 'test') RETURNING id""", UUID.class, worker, workpack, journalLine);
    }

    /**
     * A second Work Pack with a time sheet for E1021 (BPA-51: "The daily limit counts all Work Packs"). No phases or assignment: a
     * Given line is inserted by SQL, which checks neither.
     */
    UUID secondWorkPackTimesheetOf(String badge, String workpackNumber) {
        UUID workpack = jdbc.queryForObject("""
                INSERT INTO bpa.workpack (workpack_number, activity_ref, drawing_ref, department_ref, completion_date, status, assignment_state,
                                          process_instance_key, created_by, updated_by)
                VALUES (?, 'A-2201', 'DWG-118', 'Fabrication', DATE '2026-11-30', 'In Progress', 'Assigned', 9000000003, 'test', 'test')
                RETURNING id""", UUID.class, workpackNumber);
        UUID header = jdbc.queryForObject("""
                INSERT INTO bpa.hour_journal_header (workpack_id, estimated_manhours, created_by, updated_by) VALUES (?, 100, 'test', 'test') RETURNING id""",
                UUID.class, workpack);
        UUID worker = jdbc.queryForObject("SELECT id FROM bpa.worker WHERE employee_ref = ?", UUID.class, badge);
        UUID journalLine = jdbc.queryForObject("""
                INSERT INTO bpa.hour_journal_line (header_id, worker_id, role, designation, estimated_hours, created_by, updated_by)
                SELECT ?, w.id, w.role, w.designation, 100, 'test', 'test' FROM bpa.worker w WHERE w.id = ? RETURNING id""", UUID.class, header, worker);
        return jdbc.queryForObject("""
                INSERT INTO bpa.digital_timesheet (worker_id, workpack_id, journal_line_id, project_ref, wbs_ref, activity_ref, created_by, updated_by)
                VALUES (?, ?, ?, 'PRJ-139', '11031', 'A-2201', 'test', 'test') RETURNING id""", UUID.class, worker, workpack, journalLine);
    }
}
