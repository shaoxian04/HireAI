package com.hireai.domain.biz.offering.agent.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when an Agent and its first version are registered. After commit,
 * {@code AgentEmbeddingEventListener} embeds the new Agent's profile text.
 */
public record AgentRegisteredDomainEvent(UUID agentId, UUID ownerId, UUID versionId, Instant occurredAt) {
}
