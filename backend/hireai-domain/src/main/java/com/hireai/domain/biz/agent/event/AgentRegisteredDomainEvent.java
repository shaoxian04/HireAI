package com.hireai.domain.biz.agent.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when an Agent and its first version are registered. No consumer required in
 * this slice; declared as a seam for the Builder dashboard / verification workflow.
 */
public record AgentRegisteredDomainEvent(UUID agentId, UUID ownerId, UUID versionId, Instant occurredAt) {
}
