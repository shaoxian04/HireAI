package com.hireai.domain.biz.task.dispatchattempt.info;

import com.hireai.domain.biz.task.dispatchattempt.enums.DispatchOutcome;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-side view of one dispatch attempt for the task owner: the attempt row plus the Agent that
 * owns the version. {@code agentId} and {@code agentName} are null only if the version row no longer exists.
 * {@code declineReason} is the Agent's reason on a {@code CONTRACT_DECLINED} attempt (#93), else null.
 */
public record DispatchAttemptView(int attemptNumber, UUID agentId, String agentName, UUID agentVersionId,
                                  DispatchOutcome outcome, Instant occurredAt, String declineReason) {
}
