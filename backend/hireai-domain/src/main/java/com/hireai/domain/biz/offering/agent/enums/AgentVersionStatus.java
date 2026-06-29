package com.hireai.domain.biz.offering.agent.enums;

/**
 * Lifecycle of one agent version. ACTIVE is the agent's single current routable contract;
 * DEPRECATED versions are retained history (tasks bind to a specific version id, so a superseded
 * version's contract stays valid for in-flight work, Invariant #4). DRAFT is declared for a future
 * stage-before-publish flow and is not produced in this slice (publish lands a version ACTIVE).
 */
public enum AgentVersionStatus {
    DRAFT,
    ACTIVE,
    DEPRECATED
}
