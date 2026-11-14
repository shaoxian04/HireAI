package com.hireai.domain.biz.task.dispatchattempt.info;

import com.hireai.domain.biz.task.dispatchattempt.enums.DispatchOutcome;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-side view of one dispatch attempt for the task owner: the attempt row plus the Agent that
 * owns the version. {@code agentId} is null only if the version row no longer exists.
 */
public record DispatchAttemptView(int attemptNumber, UUID agentId, UUID agentVersionId,
                                  DispatchOutcome outcome, Instant occurredAt) {
}
