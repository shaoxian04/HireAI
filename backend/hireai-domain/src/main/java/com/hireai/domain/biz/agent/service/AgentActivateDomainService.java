package com.hireai.domain.biz.agent.service;

import com.hireai.domain.biz.agent.model.AgentModel;

/**
 * Domain service for the agent ACTIVATE state transition (PENDING_VERIFICATION -> ACTIVE).
 * Framework-free; delegates to the aggregate's guarded transition, which enforces the legal
 * transition and the current-version-present rule. The bean is registered in DomainServiceConfig.
 */
public interface AgentActivateDomainService {

    AgentModel activate(AgentModel agent);
}
