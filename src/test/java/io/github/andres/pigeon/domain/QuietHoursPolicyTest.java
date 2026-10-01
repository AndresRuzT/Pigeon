package io.github.andres.pigeon.domain;

import io.github.andres.pigeon.domain.policy.QuietHoursPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class QuietHoursPolicyTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    @Test
    @DisplayName("Monday 07:59 should be quiet hours and resume same day at 08:00")
    void mondayMorningBeforeEightShouldBeQuietHours() {
        // Monday 2026-10-05 07:59:00 UTC
        Instant instant = Instant.parse("2026-10-05T07:59:00Z");

        assertThat(QuietHoursPolicy.isQuietHour(instant, UTC)).isTrue();

        Instant resume = QuietHoursPolicy.calculateResumeInstant(instant, UTC);
        assertThat(resume).isEqualTo(Instant.parse("2026-10-05T08:00:00Z"));
    }

    @Test
    @DisplayName("Monday 08:00 to 17:59 should be normal business hours (not quiet hours)")
    void mondayBusinessHoursShouldNotBeQuietHours() {
        // Monday 2026-10-05 08:00:00 UTC
        Instant start = Instant.parse("2026-10-05T08:00:00Z");
        assertThat(QuietHoursPolicy.isQuietHour(start, UTC)).isFalse();

        // Monday 2026-10-05 14:30:00 UTC
        Instant mid = Instant.parse("2026-10-05T14:30:00Z");
        assertThat(QuietHoursPolicy.isQuietHour(mid, UTC)).isFalse();

        // Monday 2026-10-05 17:59:59 UTC
        Instant end = Instant.parse("2026-10-05T17:59:59Z");
        assertThat(QuietHoursPolicy.isQuietHour(end, UTC)).isFalse();
    }

    @Test
    @DisplayName("Monday 18:00 should be quiet hours and resume next morning (Tuesday 08:00)")
    void mondayEveningShouldBeQuietHoursAndResumeTuesday() {
        // Monday 2026-10-05 18:00:00 UTC
        Instant instant = Instant.parse("2026-10-05T18:00:00Z");

        assertThat(QuietHoursPolicy.isQuietHour(instant, UTC)).isTrue();

        Instant resume = QuietHoursPolicy.calculateResumeInstant(instant, UTC);
        assertThat(resume).isEqualTo(Instant.parse("2026-10-06T08:00:00Z"));
    }

    @Test
    @DisplayName("Friday 18:00 should be quiet hours and resume next business day (Monday 08:00)")
    void fridayNightShouldResumeNextMonday() {
        // Friday 2026-10-09 18:30:00 UTC
        Instant instant = Instant.parse("2026-10-09T18:30:00Z");

        assertThat(QuietHoursPolicy.isQuietHour(instant, UTC)).isTrue();

        Instant resume = QuietHoursPolicy.calculateResumeInstant(instant, UTC);
        assertThat(resume).isEqualTo(Instant.parse("2026-10-12T08:00:00Z"));
    }

    @Test
    @DisplayName("Saturday and Sunday are strictly quiet hours for the entire 24 hours")
    void weekendShouldBeEntirelyQuietHoursAndResumeMonday() {
        // Saturday 2026-10-10 12:00:00 UTC
        Instant saturday = Instant.parse("2026-10-10T12:00:00Z");
        assertThat(QuietHoursPolicy.isQuietHour(saturday, UTC)).isTrue();
        assertThat(QuietHoursPolicy.calculateResumeInstant(saturday, UTC))
                .isEqualTo(Instant.parse("2026-10-12T08:00:00Z"));

        // Sunday 2026-10-11 23:15:00 UTC
        Instant sunday = Instant.parse("2026-10-11T23:15:00Z");
        assertThat(QuietHoursPolicy.isQuietHour(sunday, UTC)).isTrue();
        assertThat(QuietHoursPolicy.calculateResumeInstant(sunday, UTC))
                .isEqualTo(Instant.parse("2026-10-12T08:00:00Z"));
    }

    @Test
    @DisplayName("Should correctly evaluate quiet hours in customer specific time zone")
    void shouldEvaluateInCustomerTimeZone() {
        // 2026-10-05 13:00:00 UTC is 09:00:00 EDT (Monday business hour in New York)
        Instant businessInNy = Instant.parse("2026-10-05T13:00:00Z");
        assertThat(QuietHoursPolicy.isQuietHour(businessInNy, NEW_YORK)).isFalse();

        // 2026-10-05 23:00:00 UTC is 19:00:00 EDT (Monday evening quiet hours in New York)
        Instant quietInNy = Instant.parse("2026-10-05T23:00:00Z");
        assertThat(QuietHoursPolicy.isQuietHour(quietInNy, NEW_YORK)).isTrue();

        Instant resumeNy = QuietHoursPolicy.calculateResumeInstant(quietInNy, NEW_YORK);
        ZonedDateTime resumeZdt = resumeNy.atZone(NEW_YORK);
        assertThat(resumeZdt.getHour()).isEqualTo(8);
        assertThat(resumeZdt.getMinute()).isEqualTo(0);
    }
}
