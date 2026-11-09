package com.hireai.domain.biz.offering.agent.info;

import java.util.UUID;

/**
 * Result of registering an Agent: its id and the AgentSigningSecret issued with it. The secret is
 * returned to the Builder this once; no read ever returns it again.
 */
public record AgentRegistration(UUID agentId, String signingSecret) {
}
