package com.lcs.star.bpa;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** BPA-43 Part A: Flyway applies the merged V1, V2 (BPA-48) and the repeatable seed (BPA-42) when the application starts. */
@SpringBootTest(properties = "camunda.client.enabled=false")
@Testcontainers
class FlywayBaselineTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void BPA43_applicationStartsAndFlywayAppliesV1V2AndSeed() {
        List<String> applied = jdbc.queryForList(
                "SELECT description FROM bpa.flyway_schema_history WHERE success ORDER BY installed_rank", String.class);
        // BPA-59: V3 (completion row source raw) is the third migration, before the repeatable seed
        // BPA-63: V4 (progress retraction task key) comes before the seed
        assertThat(applied).containsExactly("<< Flyway Schema Creation >>", "baseline schema", "epic2 timesheet to progress", "completion row source raw",
                "progress retraction task key", "seed reference data");

        Integer tables = jdbc.queryForObject(
                "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "WHERE n.nspname = 'bpa' AND c.relkind = 'r' AND c.relname <> 'flyway_schema_history'",
                Integer.class);
        assertThat(tables).as("30 in V1 plus 20 in V2 (BPA-48)").isEqualTo(50);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.worker", Integer.class)).isEqualTo(12);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.department", Integer.class)).isEqualTo(3);
    }

    /** BPA-48: the Epic 2 reference rows from the data model delta section 8 and the Jira comments; values are synthetic. */
    @Test
    void BPA48_seedCarriesTheEpic2ReferenceRows() {
        assertThat(jdbc.queryForList("SELECT employee_id FROM bpa.worker ORDER BY employee_ref", String.class)).as("every seeded Worker has an Employee ID")
                .hasSize(12).allSatisfy(id -> assertThat(id).startsWith("EMP-"));
        assertThat(jdbc.queryForList("SELECT DISTINCT employee_category FROM bpa.worker ORDER BY 1", String.class)).as("one Worker per category at least")
                .containsExactly("Contractor", "Staff", "Worker");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.worker WHERE aramco_id IS NULL", Integer.class)).as("Aramco ID is if applicable").isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.worker WHERE department_ref IS NOT NULL AND work_location IS NOT NULL AND grade IS NOT NULL",
                Integer.class)).isPositive();

        assertThat(jdbc.queryForObject("SELECT budgeted_units FROM bpa.activity WHERE activity_ref = 'A-2301'", java.math.BigDecimal.class))
                .as("A-2301 for the end-to-end demo (comment 10481)").isEqualByComparingTo("1200");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.workpack WHERE activity_ref = 'A-2301'", Integer.class)).as("no Work Packs").isZero();

        assertThat(jdbc.queryForMap("SELECT current_forecast_mh, revised_budget_cost, currency FROM bpa.cbs_forecast WHERE cbs_code_ref = 'CBS-139-PIP-01'"))
                .containsEntry("currency", "SAR");
        assertThat(jdbc.queryForObject("SELECT current_forecast_mh FROM bpa.cbs_forecast WHERE cbs_code_ref = 'CBS-139-PIP-01'", java.math.BigDecimal.class)).isEqualByComparingTo("1000");
        assertThat(jdbc.queryForObject("SELECT revised_budget_cost FROM bpa.cbs_forecast WHERE cbs_code_ref = 'CBS-139-PIP-01'", java.math.BigDecimal.class)).isEqualByComparingTo("250000");
        assertThat(jdbc.queryForObject("SELECT actual_cost FROM bpa.cbs_actual_cost WHERE cbs_code_ref = 'CBS-139-PIP-01' AND period_month = DATE '2026-11-01'",
                java.math.BigDecimal.class)).isEqualByComparingTo("180000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.cbs_forecast WHERE cbs_code_ref = 'CBS-139-PIP-02'", Integer.class)).as("a CBS with no forecast").isZero();
        assertThat(jdbc.queryForList("SELECT cbs_code_ref || ' ' || block_type || ' ' || is_closed FROM bpa.cbs_block ORDER BY cbs_code_ref", String.class))
                .containsExactly("CBS-139-PIP-01 FmsTimesheet false", "CBS-139-PIP-99 FmsTimesheet true");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.worker_leave", Integer.class)).as("one Worker on approved leave for BPA-75").isPositive();
    }
}
