package com.hireai.domain.biz.task.model;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Adds a length of business time: Monday to Friday in UTC count, Saturday and Sunday do not. A
 * multi-Item Task's windows count this way (spec S7/D10, D41), so a batch delivered on Friday
 * afternoon is still open on Monday.
 */
public final class BusinessTime {

    private BusinessTime() {
    }

    public static Instant plus(Instant from, Duration length) {
        ZonedDateTime at = from.atZone(ZoneOffset.UTC);
        Duration remaining = length;
        while (true) {
            if (isWeekend(at.getDayOfWeek())) {
                at = at.toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC);
                continue;
            }
            ZonedDateTime midnight = at.toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC);
            Duration leftToday = Duration.between(at, midnight);
            if (remaining.compareTo(leftToday) <= 0) {
                return at.plus(remaining).toInstant();
            }
            remaining = remaining.minus(leftToday);
            at = midnight;
        }
    }

    private static boolean isWeekend(DayOfWeek day) {
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }
}
