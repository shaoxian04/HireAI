package com.hireai.domain.biz.offering.agent.info;

import java.util.UUID;

/**
 * The two ids an emission site needs: which agent to credit, and who owns it.
 *
 * <p>Resolved in one query because every emission site needs both — the agent to attach the event
 * to, and the owner to compare against the task's client for the L1 self-dealing exclusion. The
 * owner lookup is deliberately unfiltered by status, exactly as findOwnerByVersionId is: an agent
 * deactivated after executing a task still earns (or loses) the standing for that work.
 */
public record AgentReputationTarget(UUID agentId, UUID ownerId) {
}
