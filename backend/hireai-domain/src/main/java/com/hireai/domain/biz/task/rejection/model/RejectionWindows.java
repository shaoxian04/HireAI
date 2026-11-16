package com.hireai.domain.biz.task.rejection.model;

import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import com.hireai.domain.biz.task.model.BusinessTime;

import java.time.Duration;
import java.time.Instant;

/**
 * The two windows of the rejection response path (spec D20/D41): how long the Agent has to respond
 * to a rejection ({@code hireai.rejection.agent-response-window}, 2h) and how long the Client then
 * has to decide after the Agent stands by ({@code hireai.rejection.client-decision-window}, 24h).
 * {@code businessDays}: the windows count only business time, as a multi-Item Task's do (D41);
 * {@link #countingBusinessDays()} gives that copy.
 */
public record RejectionWindows(Duration agentResponse, Duration clientDecision, boolean businessDays) {

    public RejectionWindows {
        requirePositive(agentResponse, "hireai.rejection.agent-response-window");
        requirePositive(clientDecision, "hireai.rejection.client-decision-window");
    }

    /** Calendar-time windows: a single-deliverable Task's. */
    public RejectionWindows(Duration agentResponse, Duration clientDecision) {
        this(agentResponse, clientDecision, false);
    }

    /** The same windows counted in business days: a multi-Item Task's sampled rejections (D17', D41). */
    public RejectionWindows countingBusinessDays() {
        return new RejectionWindows(agentResponse, clientDecision, true);
    }

    /** When the Agent's response window opened at {@code openedAt} closes. */
    public Instant responseDeadline(Instant openedAt) {
        return add(openedAt, agentResponse);
    }

    /** When the Client's decision window opened at {@code openedAt} closes. */
    public Instant decisionDeadline(Instant openedAt) {
        return add(openedAt, clientDecision);
    }

    private Instant add(Instant from, Duration length) {
        return businessDays ? BusinessTime.plus(from, length) : from.plus(length);
    }

    private static void requirePositive(Duration length, String property) {
        if (length == null || length.isZero() || length.isNegative()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, property + " must be positive; got " + length);
        }
    }
}
