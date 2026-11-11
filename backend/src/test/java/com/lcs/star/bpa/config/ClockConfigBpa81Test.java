package com.lcs.star.bpa.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * BPA-81: the QA business date. With profile {@code qa} and {@code BPA_CLOCK_TODAY=<yyyy-MM-dd>}, {@link ClockConfig} returns an offset clock whose
 * business date (in {@code app.business-zone}) is that date; time keeps moving. Without {@code qa} the variable is ignored with a WARN; a malformed
 * value stops start-up. No Docker, no database: an ApplicationContextRunner on {@code ClockConfig} only. The existing integration classes replace the
 * clock with their own @Primary bean and are not touched (plan, "What the code looks like today"). Not Gherkin; named after this task.
 */
@ExtendWith(OutputCaptureExtension.class)
class ClockConfigBpa81Test {

    private static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");
    private static final LocalDate DEC_1 = LocalDate.of(2026, 12, 1);

    private static ApplicationContextRunner runner(String[] profiles, String... properties) {
        return new ApplicationContextRunner()
                .withUserConfiguration(ClockConfig.class)
                .withInitializer(context -> {
                    if (profiles.length > 0) {
                        context.getEnvironment().setActiveProfiles(profiles);
                    }
                })
                .withPropertyValues(properties);
    }

    private static final String[] QA = {"qa"};
    private static final String[] LOCAL = {"local"};
    private static final String[] NONE = {};

    private static LocalDate businessDate(AssertableApplicationContext context) {
        return LocalDate.now(context.getBean(Clock.class).withZone(context.getBean("businessZone", ZoneId.class)));
    }

    private static void assertIsTheSystemClock(AssertableApplicationContext context) {
        assertThat(context).hasNotFailed();
        assertThat(Duration.between(Instant.now(), context.getBean(Clock.class).instant()).abs()).isLessThan(Duration.ofSeconds(5));
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    // --- profile qa with a date ---------------------------------------------------------------------------------------------

    @Test
    void BPA81_qaWithADateGivesThatBusinessDateAndAnOrderedClock() {
        runner(QA, "BPA_CLOCK_TODAY=2026-12-01").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(businessDate(context)).isEqualTo(DEC_1);
            Clock clock = context.getBean(Clock.class);
            Instant first = clock.instant();
            Instant second = clock.instant();
            assertThat(second).as("time keeps moving: two reads are ordered").isAfterOrEqualTo(first);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-12-31", "2027-01-01", "2028-02-29"})
    void BPA81_businessDateAcrossYearBoundaryAndLeapDay(String date) {
        runner(QA, "BPA_CLOCK_TODAY=" + date).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(businessDate(context)).isEqualTo(LocalDate.parse(date));
        });
    }

    @Test
    void BPA81_qaCombinesWithLocal() {
        runner(new String[] {"qa", "local"}, "BPA_CLOCK_TODAY=2026-12-01").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(businessDate(context)).isEqualTo(DEC_1);
        });
    }

    @Test
    void BPA81_offsetActiveLogsAWarningNamingTheDate(CapturedOutput output) {
        runner(QA, "BPA_CLOCK_TODAY=2026-12-01").run(context -> assertThat(context).hasNotFailed());

        assertThat(output.getAll()).contains("QA business date is 2026-12-01");
    }

    @Test
    void BPA81_whitespaceAroundTheDateIsTrimmed() {
        runner(QA, "BPA_CLOCK_TODAY=  2026-12-01 ").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(businessDate(context)).isEqualTo(DEC_1);
        });
    }

    // --- the date is read in the business zone ------------------------------------------------------------------------------

    @Test
    void BPA81_dateIsReadInTheBusinessZoneNotUtc() {
        // 21:00 to 23:59 UTC is already the next day in Riyadh (UTC+3): the two dates differ at these moments
        for (String moment : new String[] {"2026-11-11T21:00:00Z", "2026-11-11T23:59:59Z"}) {
            Clock base = Clock.fixed(Instant.parse(moment), ZoneOffset.UTC);

            Clock qa = QaClock.offsetTo(DEC_1, base, RIYADH);

            assertThat(LocalDate.ofInstant(qa.instant(), RIYADH)).as("Riyadh date at " + moment).isEqualTo(DEC_1);
            assertThat(LocalDate.ofInstant(qa.instant(), ZoneOffset.UTC)).as("the UTC date differs at " + moment).isNotEqualTo(DEC_1);
        }
    }

    @Test
    void BPA81_theZoneComesFromConfigurationNotFromRiyadh() {
        // 20:00 UTC: 23:00 on 11 Nov in Riyadh, but 01:30 on 12 Nov in Kolkata (UTC+5:30), so the number of days to 1 Dec differs
        Clock base = Clock.fixed(Instant.parse("2026-11-11T20:00:00Z"), ZoneOffset.UTC);

        Duration riyadh = Duration.between(base.instant(), QaClock.offsetTo(DEC_1, base, RIYADH).instant());
        Duration kolkata = Duration.between(base.instant(), QaClock.offsetTo(DEC_1, base, ZoneId.of("Asia/Kolkata")).instant());

        assertThat(riyadh).isEqualTo(Duration.ofDays(20));
        assertThat(kolkata).isEqualTo(Duration.ofDays(19));
        runner(QA, "BPA_CLOCK_TODAY=2026-12-01", "app.business-zone=Asia/Kolkata").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(businessDate(context)).as("read in the configured zone").isEqualTo(DEC_1);
        });
    }

    @Test
    void BPA81_offsetIsWholeDaysSoTheTimeOfDayIsKept() {
        Clock base = Clock.fixed(Instant.parse("2026-11-11T09:41:23.456Z"), ZoneOffset.UTC);

        Clock qa = QaClock.offsetTo(DEC_1, base, RIYADH);

        assertThat(Duration.between(base.instant(), qa.instant())).isEqualTo(Duration.ofDays(20));
        assertThat(LocalTime.ofInstant(qa.instant(), RIYADH)).isEqualTo(LocalTime.ofInstant(base.instant(), RIYADH));
    }

    @Test
    void BPA81_aPastDateGivesANegativeOffset() {
        Clock base = Clock.fixed(Instant.parse("2026-11-11T09:41:23Z"), ZoneOffset.UTC);

        Clock qa = QaClock.offsetTo(LocalDate.of(2026, 10, 1), base, RIYADH);

        assertThat(Duration.between(base.instant(), qa.instant())).isEqualTo(Duration.ofDays(-41));
        assertThat(LocalDate.ofInstant(qa.instant(), RIYADH)).isEqualTo(LocalDate.of(2026, 10, 1));
    }

    // --- without profile qa, or with nothing set -----------------------------------------------------------------------------

    @Test
    void BPA81_withoutQaTheVariableIsIgnoredAndWarned(CapturedOutput output) {
        runner(LOCAL, "BPA_CLOCK_TODAY=2026-12-01").run(ClockConfigBpa81Test::assertIsTheSystemClock);

        assertThat(output.getAll()).contains("BPA_CLOCK_TODAY=2026-12-01 is set but profile qa is not active");
    }

    @Test
    void BPA81_withNoProfileAtAllTheVariableIsIgnoredAndWarned(CapturedOutput output) {
        runner(NONE, "BPA_CLOCK_TODAY=2026-12-01").run(ClockConfigBpa81Test::assertIsTheSystemClock);

        assertThat(output.getAll()).contains("BPA_CLOCK_TODAY=2026-12-01 is set but profile qa is not active");
    }

    @Test
    void BPA81_aMalformedValueWithoutQaIsIgnoredNotFatal(CapturedOutput output) {
        runner(LOCAL, "BPA_CLOCK_TODAY=not-a-date").run(ClockConfigBpa81Test::assertIsTheSystemClock);

        assertThat(output.getAll()).contains("is set but profile qa is not active");
    }

    @Test
    void BPA81_unsetOrEmptyVariableKeepsTheSystemClockWithoutAWarning(CapturedOutput output) {
        runner(QA).run(ClockConfigBpa81Test::assertIsTheSystemClock);
        runner(QA, "BPA_CLOCK_TODAY=").run(ClockConfigBpa81Test::assertIsTheSystemClock);
        runner(QA, "BPA_CLOCK_TODAY=   ").run(ClockConfigBpa81Test::assertIsTheSystemClock);
        runner(LOCAL, "BPA_CLOCK_TODAY=").run(ClockConfigBpa81Test::assertIsTheSystemClock);
        runner(NONE).run(ClockConfigBpa81Test::assertIsTheSystemClock);

        assertThat(output.getAll()).doesNotContain("BPA_CLOCK_TODAY").doesNotContain("QA business date");
    }

    // --- a malformed value stops start-up ------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"2026-02-30", "2026-13-01", "01-12-2026", "tomorrow", "2026-12-01T00:00:00Z", "2026-1-1"})
    void BPA81_malformedValueFailsStartUpNamingTheVariableAndValue(String value) {
        runner(QA, "BPA_CLOCK_TODAY=" + value).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure()).getMessage()).contains("BPA_CLOCK_TODAY").contains("'" + value + "'")
                    .contains("yyyy-MM-dd");
        });
    }

}
