package com.lcs.star.bpa;

import static org.assertj.core.api.Assertions.assertThat;

import com.lcs.star.bpa.progress.MonthLockService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BPA-64 (D5, FRD-22 §22.8.1): when a month locks (hours.monthLockDay, 1 in the default settings), the approved man-hours by CBS and activity dated in that
 * month are frozen in cbs_manhour_snapshot and never change. The job calls {@link MonthLockService#lockDueMonth} once a day with the business date; these
 * tests call it with the date, so no clock is needed. Plan Q1 (A): a scheduled job, no BPMN change; refusing hours in a locked month is BPA-67. Scenario 13's
 * "the December 2026 actual shows 16.00 MH" is read as the December snapshot, frozen when December locks on 1 Jan 2027 (the weekly CBS actuals are
 * cumulative, so a month figure is the snapshot; an ASSUMPTION of the plan).
 */
@Tag("integration")
@SpringBootTest(properties = "camunda.client.enabled=false")
@Testcontainers
class MonthSnapshotTest {

    static final LocalDate NOVEMBER = LocalDate.of(2026, 11, 1);
    static final LocalDate DECEMBER = LocalDate.of(2026, 12, 1);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MonthLockService locks;

    private BookHoursFixture fixture;
    private BookHoursFixture.FmsIds sheets;

    @BeforeEach
    void background() {
        fixture = new BookHoursFixture(jdbc);
        BookHoursFixture.Ids ids = fixture.reset();
        sheets = fixture.fmsBackground(ids); // CBS-139-PIP-01 / A-2201 on E1021 and E1099; WP-000470's sheet has no CBS
        // CBS-139-PIP-01 / A-2201 has 640.00 approved man-hours dated in November 2026; lines that do not count are there too
        fixture.line(sheets.e1021(), LocalDate.of(2026, 11, 3), "Cutting", new BigDecimal("400.00"), "Approved");
        fixture.line(sheets.e1099(), LocalDate.of(2026, 11, 4), "Cutting", new BigDecimal("240.00"), "Approved");
        fixture.line(sheets.e1021(), LocalDate.of(2026, 11, 5), "Cutting", new BigDecimal("30.00"), "Submitted");
        fixture.line(sheets.e1021(), LocalDate.of(2026, 11, 6), "Cutting", new BigDecimal("20.00"), "Rejected");
        fixture.line(sheets.e1150OnWp470(), LocalDate.of(2026, 11, 7), "Welding", new BigDecimal("40.00"), "Approved"); // no CBS (D40): no snapshot row
    }

    @Test
    void BPA64_monthLockFreezesManhourSnapshot() {
        Optional<LocalDate> locked = locks.lockDueMonth(DECEMBER, "bpa-service"); // November 2026 locks on 1 Dec 2026

        assertThat(locked).contains(NOVEMBER);
        assertThat(snapshot(NOVEMBER)).isEqualByComparingTo("640.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.cbs_manhour_snapshot WHERE month = ?", Integer.class, java.sql.Date.valueOf(NOVEMBER)))
                .as("one row for CBS-139-PIP-01 / A-2201; the line with no CBS has none").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT lock_day FROM bpa.hours_month_lock WHERE month = ?", Integer.class, java.sql.Date.valueOf(NOVEMBER))).isEqualTo(1);
    }

    @Test
    void BPA64_closedMonthSnapshotNeverChanges() {
        locks.lockDueMonth(DECEMBER, "bpa-service");
        assertThat(snapshot(NOVEMBER)).isEqualByComparingTo("640.00");

        // 16.00 hours dated 2 Dec 2026 are approved, and an approval dated in November comes late
        fixture.line(sheets.e1021(), LocalDate.of(2026, 12, 2), "Cutting", new BigDecimal("16.00"), "Approved");
        fixture.line(sheets.e1099(), LocalDate.of(2026, 11, 20), "Cutting", new BigDecimal("8.00"), "Approved");
        Optional<LocalDate> again = locks.lockDueMonth(DECEMBER.plusDays(1), "bpa-service");

        assertThat(again).as("November is locked already").isEmpty();
        assertThat(snapshot(NOVEMBER)).as("the November snapshot still holds 640.00").isEqualByComparingTo("640.00");
        // December locks on 1 Jan 2027: its snapshot shows the 16.00 MH
        assertThat(locks.lockDueMonth(LocalDate.of(2027, 1, 1), "bpa-service")).contains(DECEMBER);
        assertThat(snapshot(DECEMBER)).isEqualByComparingTo("16.00");
        assertThat(snapshot(NOVEMBER)).isEqualByComparingTo("640.00");
    }

    // --- extras (not Gherkin scenarios) ---------------------------------------------------------------------------------------

    @Test
    void aMonthLockedTwiceIsFrozenOnce() {
        locks.lockDueMonth(DECEMBER, "bpa-service");
        locks.lockDueMonth(DECEMBER, "bpa-service");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.hours_month_lock WHERE month = ?", Integer.class, java.sql.Date.valueOf(NOVEMBER))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bpa.cbs_manhour_snapshot WHERE month = ?", Integer.class, java.sql.Date.valueOf(NOVEMBER))).isEqualTo(1);
    }

    private BigDecimal snapshot(LocalDate month) {
        return jdbc.queryForObject("SELECT approved_manhours FROM bpa.cbs_manhour_snapshot WHERE month = ? AND cbs_code_ref = 'CBS-139-PIP-01' AND activity_ref = 'A-2201'",
                BigDecimal.class, java.sql.Date.valueOf(month));
    }
}
