package com.hireai.domain.biz.agent.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when an Agent is activated (PENDING_VERIFICATION -> ACTIVE) and becomes
 * routable. No consumer required in this slice.
 */
public record AgentActivatedDomainEvent(UUID agentId, UUID ownerId, UUID currentVersionId, Instant occurredAt) {
}
