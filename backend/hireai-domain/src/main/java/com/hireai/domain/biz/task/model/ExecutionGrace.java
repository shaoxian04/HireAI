package com.hireai.domain.biz.task.model;

import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.time.Instant;

/**
 * The slack added to an Agent's declared max execution time when the platform stamps a task's
 * execution deadline ({@code hireai.execution.grace-seconds}). It absorbs the dispatch path (send
 * attempts, retry delays, platform re-schedules) so that none of it eats into the Agent's own time,
 * and every client-deadline fit counts it too. One configured value, read once.
 */
public record ExecutionGrace(long seconds) {

    public ExecutionGrace {
        // A negative grace would stamp deadlines in the past: every dispatch would be refunded by the
        // timeout sweeper right after routing.
        if (seconds < 0) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "hireai.execution.grace-seconds must be >= 0; got " + seconds);
        }
    }

    /** The execution deadline of a dispatch, starting at {@code now}, to an Agent declaring {@code maxExecutionSeconds}. */
    public Instant executionDeadline(Instant now, int maxExecutionSeconds) {
        return now.plusSeconds(maxExecutionSeconds + seconds);
    }
}
