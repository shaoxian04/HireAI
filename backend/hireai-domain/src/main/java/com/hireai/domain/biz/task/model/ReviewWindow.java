package com.hireai.domain.biz.task.model;

import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * How long a Client has to review a result that passed validation ({@code hireai.review.window},
 * 24h by default, spec D41). When it closes, the Client's silence counts as acceptance and the task
 * is settled as accepted (D10). One configured value, read once.
 */
public record ReviewWindow(Duration length) {

    public ReviewWindow {
        // A zero or negative window would auto-accept every result the moment it passed validation,
        // taking the Client's review away entirely.
        if (length == null || length.isZero() || length.isNegative()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "hireai.review.window must be positive; got " + length);
        }
    }

    /** When a window opened at {@code openedAt} closes. */
    public Instant closesAt(Instant openedAt) {
        return openedAt.plus(length);
    }

    /**
     * When a window opened at {@code openedAt} closes if only business time counts: a multi-Item
     * Task's window (spec S7/D10). Monday to Friday in UTC uses the window up; Saturday and Sunday
     * do not, so a batch delivered on Friday afternoon is still open on Monday.
     */
    public Instant closesAtCountingBusinessDays(Instant openedAt) {
        ZonedDateTime at = openedAt.atZone(ZoneOffset.UTC);
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

    /** Whether a window closing at {@code deadline} has closed by {@code now}; never, without a deadline. */
    public static boolean hasClosed(Instant deadline, Instant now) {
        return deadline != null && !now.isBefore(deadline);
    }
}
