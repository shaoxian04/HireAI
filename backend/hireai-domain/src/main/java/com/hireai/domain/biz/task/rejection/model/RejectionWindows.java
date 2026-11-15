package com.hireai.domain.biz.task.rejection.model;

import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.time.Duration;

/**
 * The two windows of the rejection response path (spec D20/D41): how long the Agent has to respond
 * to a rejection ({@code hireai.rejection.agent-response-window}, 2h) and how long the Client then
 * has to decide after the Agent stands by ({@code hireai.rejection.client-decision-window}, 24h).
 */
public record RejectionWindows(Duration agentResponse, Duration clientDecision) {

    public RejectionWindows {
        requirePositive(agentResponse, "hireai.rejection.agent-response-window");
        requirePositive(clientDecision, "hireai.rejection.client-decision-window");
    }

    private static void requirePositive(Duration length, String property) {
        if (length == null || length.isZero() || length.isNegative()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, property + " must be positive; got " + length);
        }
    }
}
