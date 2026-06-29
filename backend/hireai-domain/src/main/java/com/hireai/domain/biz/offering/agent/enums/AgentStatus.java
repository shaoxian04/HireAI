package com.hireai.domain.biz.offering.agent.enums;

/**
 * Agent lifecycle. Only PENDING_VERIFICATION -> ACTIVE is implemented in this slice;
 * SUSPENDED and DEACTIVATED are declared for forward-compatibility and land with the
 * Builder dashboard / moderation module. Only ACTIVE agents are routable.
 */
public enum AgentStatus {
    PENDING_VERIFICATION,
    ACTIVE,
    SUSPENDED,
    DEACTIVATED
}
