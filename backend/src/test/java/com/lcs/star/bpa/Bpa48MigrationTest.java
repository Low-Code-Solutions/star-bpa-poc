package com.lcs.star.bpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BPA-48: {@code V2__epic2_timesheet_to_progress.sql} on a database that already holds Epic 1 data (the QA dev server's case), and the
 * shape of the Epic 2 tables (docs/data-model.md v0.7). Flyway alone, no application: V1 is applied first, Epic 1 rows are written by
 * hand, then the rest is migrated. V1 stays frozen (Q-DM21).
 */
@Testcontainers
class Bpa48MigrationTest {

    // one container per test: the first test needs a database that has only V1, so tests must not share one
    @Container
    final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    private static final List<String> EPIC2_TABLES = List.of("hours_submission", "hours_decision", "timesheet_file", "timesheet_file_row",
            "hours_month_lock", "progress_period", "fms_hours_export", "completion_report", "completion_report_row", "workpack_progress",
            "progress_retraction", "activity_progress", "cbs_manhour_snapshot", "cbs_forecast", "cbs_actual_cost", "cbs_block",
            "variance_alert", "cbs_review", "user_project", "worker_leave");

    private DataSource dataSource() {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource()).schemas("bpa").target(target).load();
    }

    /** V1 alone: Flyway runs repeatable migrations whatever the target, and the seed needs the V2 columns, so it is given a prefix that matches nothing. */
    private Flyway v1Only() {
        return Flyway.configure().dataSource(dataSource()).schemas("bpa").target("1").repeatableSqlMigrationPrefix("NO_REPEATABLES_YET").load();
    }

    @Test
    void BPA48_v2MigratesADatabaseThatHoldsEpicOneData() {
        v1Only().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'bpa' AND table_name = 'worker' "
                + "AND column_name = 'employee_id'", Integer.class)).as("V1 alone has no employee_id").isZero();

        // Epic 1 data: a Worker (badge E7001), a Work Pack, its journal, a time sheet and a line with the V1 columns only.
        jdbc.update("INSERT INTO bpa.worker (employee_ref, name, role, designation, agency_flag, created_by, updated_by) "
                + "VALUES ('E7001', 'Worker E7001', 'Welder', 'Grade 1', false, 'test', 'test') ON CONFLICT (employee_ref) DO NOTHING");
        jdbc.update("INSERT INTO bpa.department (department_ref, name, created_by, updated_by) VALUES ('Fabrication', 'F', 'test', 'test') "
                + "ON CONFLICT (department_ref) DO NOTHING");
        jdbc.update("INSERT INTO bpa.project_job_code (project_ref, job_code_ref, created_by, updated_by) VALUES ('PRJ-139', 'JC-139', 'test', 'test') "
                + "ON CONFLICT (project_ref) DO NOTHING");
        jdbc.update("INSERT INTO bpa.wbs (wbs_ref, project_ref, created_by, updated_by) VALUES ('11031', 'PRJ-139', 'test', 'test') ON CONFLICT (wbs_ref) DO NOTHING");
        jdbc.update("INSERT INTO bpa.activity (activity_ref, activity_name, wbs_ref, created_by, updated_by) VALUES ('A-7001', 'a', '11031', 'test', 'test') "
                + "ON CONFLICT (activity_ref) DO NOTHING");
        jdbc.update("INSERT INTO bpa.drawing (drawing_ref, title, created_by, updated_by) VALUES ('DWG-7001', 'd', 'test', 'test') ON CONFLICT (drawing_ref) DO NOTHING");
        jdbc.update("INSERT INTO bpa.workpack (workpack_number, activity_ref, drawing_ref, department_ref, completion_date, process_instance_key, created_by, updated_by) "
                + "VALUES ('WP-700001', 'A-7001', 'DWG-7001', 'Fabrication', DATE '2026-12-01', 7001, 'test', 'test')");
        jdbc.update("INSERT INTO bpa.hour_journal_header (workpack_id, estimated_manhours, created_by, updated_by) "
                + "SELECT id, 10, 'test', 'test' FROM bpa.workpack WHERE workpack_number = 'WP-700001'");
        jdbc.update("INSERT INTO bpa.hour_journal_line (header_id, worker_id, role, designation, estimated_hours, created_by, updated_by) "
                + "SELECT h.id, w.id, 'Welder', 'Grade 1', 10, 'test', 'test' FROM bpa.hour_journal_header h, bpa.worker w WHERE w.employee_ref = 'E7001'");
        jdbc.update("INSERT INTO bpa.digital_timesheet (worker_id, workpack_id, journal_line_id, project_ref, wbs_ref, activity_ref, created_by, updated_by) "
                + "SELECT l.worker_id, h.workpack_id, l.id, 'PRJ-139', '11031', 'A-7001', 'test', 'test' FROM bpa.hour_journal_line l JOIN bpa.hour_journal_header h ON h.id = l.header_id");
        jdbc.update("INSERT INTO bpa.digital_timesheet_line (digital_timesheet_id, work_date, phase, hours, created_by, updated_by) "
                + "SELECT id, DATE '2026-11-04', 'Cutting', 0, 'test', 'test' FROM bpa.digital_timesheet");

        flyway("latest").migrate();

        assertThat(jdbc.queryForList("SELECT description FROM bpa.flyway_schema_history WHERE success ORDER BY installed_rank", String.class))
                .contains("epic2 timesheet to progress");
        assertThat(jdbc.queryForObject("SELECT employee_id FROM bpa.worker WHERE employee_ref = 'E7001'", String.class))
                .as("existing Workers are filled in V2 as 'EMP-' || employee_ref before NOT NULL").isEqualTo("EMP-E7001");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.worker WHERE employee_id IS NULL", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_schema = 'bpa' AND table_name = 'worker' "
                + "AND column_name = 'employee_id'", String.class)).isEqualTo("NO");
        assertThat(jdbc.queryForMap("SELECT status, entry_source, hours_submission_id, remarks FROM bpa.digital_timesheet_line"))
                .as("the existing line takes the defaults and keeps its 0 hours").containsEntry("status", "Draft").containsEntry("entry_source", "Form")
                .containsEntry("hours_submission_id", null).containsEntry("remarks", null);
        assertThat(jdbc.queryForObject("SELECT hours FROM bpa.digital_timesheet_line", java.math.BigDecimal.class)).isEqualByComparingTo("0");
    }

    @Test
    void BPA48_v2AddsTheEpic2TablesWithSorCommentsAndLeavesOutTheParkedOnes() {
        flyway("latest").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());

        assertThat(jdbc.queryForList("SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'bpa' "
                + "AND c.relkind = 'r' AND c.relname <> 'flyway_schema_history'", String.class)).as("30 tables in V1 plus 20 in V2")
                .hasSize(50).containsAll(EPIC2_TABLES);
        assertThat(SorComments.tablesWithoutComment(jdbc)).as("S17: every table carries an SOR comment").isEmpty();
        assertThat(jdbc.queryForList("SELECT relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'bpa' "
                + "AND relname IN ('hours_approval', 'cbs_reopen_request')", String.class)).as("parked with BPA-71 and BPA-70").isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'bpa' "
                + "AND table_name = 'hours_submission' AND column_name = 'approval_route'", Integer.class)).as("parked with BPA-71").isZero();

        // the additions from the review comments (BPA-48 comments 10481, 10498)
        assertThat(columns(jdbc, "workpack_progress")).contains("override_remark", "reported_pct", "approved_pct", "previous_pct", "rule_result");
        assertThat(columns(jdbc, "progress_retraction")).contains("approved_pct");
        assertThat(columns(jdbc, "cbs_manhour_snapshot")).contains("month", "cbs_code_ref", "activity_ref", "approved_manhours", "frozen_at");
        assertThat(columns(jdbc, "worker")).contains("employee_id", "department_ref", "employee_category", "work_location", "grade", "aramco_id");
        assertThat(columns(jdbc, "digital_timesheet_line")).contains("status", "hours_submission_id", "entry_source", "timesheet_file_id", "remarks",
                "exported_at", "fms_hours_export_id", "reported_completion_pct");
    }

    @Test
    void BPA48_v2ConstraintsHoldTheRulesTheDatabaseCanHold() {
        flyway("latest").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());

        assertThatThrownBy(() -> jdbc.update("INSERT INTO bpa.cbs_block (cbs_code_ref, block_type, created_by, updated_by) VALUES ('CBS-X', 'Other', 't', 't')"))
                .as("block_type is one of the FRD-22 §22.3.1 types").hasMessageContaining("cbs_block_block_type_check");
        jdbc.update("INSERT INTO bpa.cbs_block (cbs_code_ref, block_type, created_by, updated_by) VALUES ('CBS-X', 'FmsTimesheet', 't', 't')");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO bpa.cbs_block (cbs_code_ref, block_type, created_by, updated_by) VALUES ('CBS-X', 'FmsTimesheet', 't', 't')"))
                .as("UNIQUE (cbs_code_ref, block_type)").hasMessageContaining("cbs_block_cbs_code_ref_block_type_key");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO bpa.hours_month_lock (month, locked_at, lock_day, created_by, updated_by) VALUES (DATE '2026-11-01', now(), 5, 't', 't')"))
                .as("lock day is 1 or 16").hasMessageContaining("hours_month_lock_lock_day_check");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO bpa.worker (employee_ref, employee_id, name, role, designation, agency_flag, employee_category, created_by, updated_by) "
                + "VALUES ('E7002', 'EMP-E7002', 'W', 'r', 'd', false, 'Intern', 't', 't')")).as("category is Staff, Worker or Contractor").hasMessageContaining("worker_employee_category_check");
    }

    /** A Draft time sheet on the seeded Worker E1021 and seeded activity, as Epic 1 would have left it; returns the time sheet id. */
    private static java.util.UUID timesheetOnSeedData(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO bpa.workpack (workpack_number, activity_ref, drawing_ref, department_ref, completion_date, process_instance_key, created_by, updated_by) "
                + "SELECT 'WP-700002', 'A-2201', (SELECT min(drawing_ref) FROM bpa.drawing), 'Fabrication', DATE '2026-12-01', 7002, 'test', 'test'");
        jdbc.update("INSERT INTO bpa.hour_journal_header (workpack_id, estimated_manhours, created_by, updated_by) "
                + "SELECT id, 10, 'test', 'test' FROM bpa.workpack WHERE workpack_number = 'WP-700002'");
        jdbc.update("INSERT INTO bpa.hour_journal_line (header_id, worker_id, role, designation, estimated_hours, created_by, updated_by) "
                + "SELECT h.id, w.id, 'Welder', 'Grade 3', 10, 'test', 'test' FROM bpa.hour_journal_header h, bpa.worker w WHERE w.employee_ref = 'E1021'");
        jdbc.update("INSERT INTO bpa.digital_timesheet (worker_id, workpack_id, journal_line_id, project_ref, wbs_ref, activity_ref, created_by, updated_by) "
                + "SELECT l.worker_id, h.workpack_id, l.id, 'PRJ-139', '11031', 'A-2201', 'test', 'test' FROM bpa.hour_journal_line l JOIN bpa.hour_journal_header h ON h.id = l.header_id");
        return jdbc.queryForObject("SELECT id FROM bpa.digital_timesheet", java.util.UUID.class);
    }

    @Test
    void BPA48_oneLinePerWorkerDatePhaseIgnoresRejectedLines() {
        flyway("latest").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        java.util.UUID sheet = timesheetOnSeedData(jdbc);
        String insert = "INSERT INTO bpa.digital_timesheet_line (digital_timesheet_id, work_date, phase, hours, status, created_by, updated_by) "
                + "VALUES (?, DATE '2026-11-04', 'Cutting', 8, ?, 't', 't')";

        jdbc.update(insert, sheet, "Draft");
        assertThatThrownBy(() -> jdbc.update(insert, sheet, "Draft")).as("a second line for the same Worker, date and phase (E2-D4)")
                .hasMessageContaining("digital_timesheet_line_one_per_worker_date_phase");
        jdbc.update("UPDATE bpa.digital_timesheet_line SET status = 'Rejected'");
        jdbc.update(insert, sheet, "Draft"); // E2-06 c: a Rejected line does not hold the key, so a new line for it can be booked
        jdbc.update(insert, sheet, "Rejected"); // and any number of Rejected lines may stay
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.digital_timesheet_line", Integer.class)).isEqualTo(3);
        assertThatThrownBy(() -> jdbc.update(insert, sheet, "Submitted")).as("the new line holds the key again")
                .hasMessageContaining("digital_timesheet_line_one_per_worker_date_phase");
    }

    @Test
    void BPA48_hoursCannotBeNegativeAndEmployeeIdIsUnique() {
        flyway("latest").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        java.util.UUID sheet = timesheetOnSeedData(jdbc);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO bpa.digital_timesheet_line (digital_timesheet_id, work_date, phase, hours, created_by, updated_by) "
                + "VALUES (?, DATE '2026-11-04', 'Cutting', -0.01, 't', 't')", sheet)).hasMessageContaining("digital_timesheet_line_hours_check");
        jdbc.update("INSERT INTO bpa.digital_timesheet_line (digital_timesheet_id, work_date, phase, hours, created_by, updated_by) "
                + "VALUES (?, DATE '2026-11-04', 'Cutting', 0, 't', 't')", sheet); // 0 is allowed in the database (Q-DM16)

        assertThatThrownBy(() -> jdbc.update("INSERT INTO bpa.worker (employee_ref, employee_id, name, role, designation, agency_flag, created_by, updated_by) "
                + "VALUES ('E7003', 'EMP-E1021', 'W', 'r', 'd', false, 't', 't')")).as("Q-DM22").hasMessageContaining("worker_employee_id_key");
    }

    @Test
    void BPA48_seedCanBeRunAgainOnAMigratedDatabaseWithoutChangingIt() throws Exception {
        flyway("latest").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        String counts = "SELECT (SELECT count(*) FROM bpa.worker) || '/' || (SELECT count(*) FROM bpa.activity) || '/' || (SELECT count(*) FROM bpa.cbs_block) "
                + "|| '/' || (SELECT count(*) FROM bpa.cbs_forecast) || '/' || (SELECT count(*) FROM bpa.cbs_actual_cost) || '/' || (SELECT count(*) FROM bpa.worker_leave) "
                + "|| '/' || (SELECT count(*) FROM bpa.cbs_activity)";
        String before = jdbc.queryForObject(counts, String.class);
        String seed;
        try (var in = new org.springframework.core.io.ClassPathResource("db/migration/R__seed_reference_data.sql").getInputStream()) {
            seed = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }

        jdbc.execute(seed); // what Flyway runs again when the file's checksum changes, on a database that already has these rows

        assertThat(jdbc.queryForObject(counts, String.class)).as("every statement is idempotent (A8)").isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.worker WHERE employee_id <> 'EMP-' || employee_ref", Integer.class)).isZero();
    }

    private static List<String> columns(JdbcTemplate jdbc, String table) {
        return jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = 'bpa' AND table_name = ?", String.class, table);
    }
}
