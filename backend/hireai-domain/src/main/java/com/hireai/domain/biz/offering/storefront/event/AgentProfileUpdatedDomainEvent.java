package com.hireai.domain.biz.offering.storefront.event;

import java.time.Instant;
import java.util.UUID;

/** Published when a Builder saves an Agent's storefront profile; the profile vector is refreshed after commit. */
public record AgentProfileUpdatedDomainEvent(UUID agentId, Instant occurredAt) {
}
