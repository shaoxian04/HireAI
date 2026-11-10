package com.hireai.domain.biz.task.model;

import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.time.Duration;
import java.time.Instant;

/**
 * The Client's absolute deadline for a task (#83): when the result is needed by. Fixed once, when the
 * task is submitted (or, for a match preview, when the preview is requested), from the
 * {@code deadlineSeconds} the Client sent.
 *
 * <p>The one eligibility rule: an Agent fits only if {@code now + maxExecutionSeconds + grace <= at},
 * so the execution deadline routing stamps on the task never falls after the Client's deadline. It
 * is re-applied at every selection (shortlist, initial routing, each failover hop) with that
 * moment's {@code now}, so time spent on a failed Agent narrows the field.
 */
public record ClientDeadline(Instant at) {

    /** Longest deadline a Client may set: 30 days, far beyond any Agent's max execution time. */
    public static final long MAX_SECONDS = 2_592_000L;

    public ClientDeadline {
        if (at == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "client deadline is required");
        }
    }

    /** The deadline {@code seconds} after {@code from}; {@code seconds} must be in 1..{@link #MAX_SECONDS}. */
    public static ClientDeadline after(Instant from, long seconds) {
        if (seconds <= 0 || seconds > MAX_SECONDS) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "deadlineSeconds must be between 1 and " + MAX_SECONDS);
        }
        return new ClientDeadline(from.plusSeconds(seconds));
    }

    /**
     * The longest max execution time an Agent may declare and still fit, selecting at {@code now}.
     * Whole seconds, rounded down (a part second left over never admits an extra second); negative
     * once even an instant Agent would overrun.
     */
    public long longestExecutionSeconds(Instant now, ExecutionGrace grace) {
        // Duration.getSeconds() floors, also for negative durations.
        return Duration.between(now, at).getSeconds() - grace.seconds();
    }

    /** Whether an Agent declaring {@code maxExecutionSeconds} fits, selecting at {@code now}. */
    public boolean admits(int maxExecutionSeconds, Instant now, ExecutionGrace grace) {
        return !grace.executionDeadline(now, maxExecutionSeconds).isAfter(at);
    }

    /**
     * A booking never substitutes another Agent, so one that can't finish by this deadline (booked at
     * {@code now}) is refused up front with a 400 naming why, before anything is frozen (#83).
     */
    public void requireAdmits(int maxExecutionSeconds, Instant now, ExecutionGrace grace) {
        if (!admits(maxExecutionSeconds, now, grace)) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "This agent can take up to " + maxExecutionSeconds + "s (plus " + grace.seconds()
                            + "s grace), so it can't finish within the " + Duration.between(now, at).getSeconds()
                            + "s deadline");
        }
    }
}
