package com.hireai.controller.biz.task.dto;

import com.hireai.domain.biz.task.dispatchattempt.info.DispatchAttemptView;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of a task's dispatch history (#75): which Agent (and version) the platform tried, in
 * which order, and how it ended ({@code ACCEPTED}, {@code UNREACHABLE}, {@code BUSY},
 * {@code UNAVAILABLE}). Read-only.
 */
public record DispatchAttemptDTO(
        int attemptNumber,
        UUID agentId,
        UUID agentVersionId,
        String outcome,
        Instant occurredAt
) {
    public static DispatchAttemptDTO from(DispatchAttemptView view) {
        return new DispatchAttemptDTO(view.attemptNumber(), view.agentId(), view.agentVersionId(),
                view.outcome().name(), view.occurredAt());
    }
}
