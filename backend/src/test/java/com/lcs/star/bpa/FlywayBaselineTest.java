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

/** BPA-43 Part A: Flyway applies the merged V1 and the repeatable seed (BPA-42) when the application starts. */
@SpringBootTest(properties = "camunda.client.enabled=false")
@Testcontainers
class FlywayBaselineTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void BPA43_applicationStartsAndFlywayAppliesV1AndSeed() {
        List<String> applied = jdbc.queryForList(
                "SELECT description FROM bpa.flyway_schema_history WHERE success ORDER BY installed_rank", String.class);
        assertThat(applied).containsExactly("<< Flyway Schema Creation >>", "baseline schema", "seed reference data");

        Integer tables = jdbc.queryForObject(
                "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "WHERE n.nspname = 'bpa' AND c.relkind = 'r' AND c.relname <> 'flyway_schema_history'",
                Integer.class);
        assertThat(tables).isEqualTo(31);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.worker", Integer.class)).isEqualTo(12);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.department", Integer.class)).isEqualTo(3);
    }
}
