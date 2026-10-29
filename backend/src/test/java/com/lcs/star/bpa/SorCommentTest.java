package com.lcs.star.bpa;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** BPA-43 Part A, S17: every table in schema bpa has an SOR comment (same exclusion as BPA-42). */
@SpringBootTest(properties = "camunda.client.enabled=false")
@Testcontainers
class SorCommentTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @Test
    void BPA43_everyTableInBpaHasSorComment() {
        assertThat(SorComments.tablesWithoutComment(jdbc)).isEmpty();
    }

    @Test
    void BPA43_sorCommentCheckReportsAnUncommentedTable() {
        // DDL is transactional in PostgreSQL: the probe table never survives the test.
        tx.executeWithoutResult(status -> {
            jdbc.execute("CREATE TABLE bpa.sor_probe (id uuid)");
            assertThat(SorComments.tablesWithoutComment(jdbc)).containsExactly("sor_probe");
            status.setRollbackOnly();
        });
        assertThat(SorComments.tablesWithoutComment(jdbc)).isEmpty();
    }
}
