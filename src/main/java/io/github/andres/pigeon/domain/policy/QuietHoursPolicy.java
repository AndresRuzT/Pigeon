package io.github.andres.pigeon.domain.policy;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Objects;

/**
 * Domain policy enforcing quiet hours restrictions.
 * Business rule:
 * - Normal working hours are Monday through Friday, 08:00 (inclusive) to 18:00 (exclusive).
 * - All other hours (before 08:00 or after 18:00 on weekdays, and all 24 hours of Saturday and Sunday)
 *   are strictly quiet hours.
 * - Non-security notifications arriving during quiet hours must be deferred until the next business window.
 */
public final class QuietHoursPolicy {

    public static final LocalTime BUSINESS_START = LocalTime.of(8, 0);
    public static final LocalTime BUSINESS_END = LocalTime.of(18, 0);

    private QuietHoursPolicy() {}

    /**
     * Determines whether the given instant falls into quiet hours in the customer's time zone.
     *
     * @param instant the instant to test
     * @param zoneId  the customer's time zone (defaults to UTC if null)
     * @return true if quiet hours are active, false if within normal business hours
     */
    public static boolean isQuietHour(Instant instant, ZoneId zoneId) {
        Objects.requireNonNull(instant, "instant cannot be null");
        ZoneId effectiveZone = zoneId != null ? zoneId : ZoneId.of("UTC");
        ZonedDateTime zdt = instant.atZone(effectiveZone);
        DayOfWeek dow = zdt.getDayOfWeek();

        // Weekends (Saturday and Sunday) are quiet hours
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return true;
        }

        LocalTime time = zdt.toLocalTime();
        // Outside 08:00 - 18:00 on weekdays
        return time.isBefore(BUSINESS_START) || !time.isBefore(BUSINESS_END);
    }

    /**
     * Calculates the next resume instant when quiet hours conclude (08:00 on the next business day).
     *
     * @param instant the current instant
     * @param zoneId  the customer's time zone (defaults to UTC if null)
     * @return the instant when notifications may resume delivery
     */
    public static Instant calculateResumeInstant(Instant instant, ZoneId zoneId) {
        Objects.requireNonNull(instant, "instant cannot be null");
        ZoneId effectiveZone = zoneId != null ? zoneId : ZoneId.of("UTC");
        ZonedDateTime zdt = instant.atZone(effectiveZone);
        DayOfWeek dow = zdt.getDayOfWeek();
        LocalTime time = zdt.toLocalTime();

        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return zdt.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                    .with(BUSINESS_START)
                    .toInstant();
        }

        if (time.isBefore(BUSINESS_START)) {
            // Same day morning at 08:00
            return zdt.with(BUSINESS_START).toInstant();
        }

        if (!time.isBefore(BUSINESS_END)) {
            // Evening after 18:00
            if (dow == DayOfWeek.FRIDAY) {
                return zdt.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                        .with(BUSINESS_START)
                        .toInstant();
            } else {
                return zdt.plusDays(1).with(BUSINESS_START).toInstant();
            }
        }

        // Already within business hours
        return instant;
    }
}
