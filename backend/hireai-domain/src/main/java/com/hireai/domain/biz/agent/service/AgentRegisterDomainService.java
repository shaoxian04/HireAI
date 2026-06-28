package com.hireai.domain.biz.agent.service;

import com.hireai.domain.biz.agent.info.AgentRegisterInfo;
import com.hireai.domain.biz.agent.model.AgentModel;

/**
 * Domain service for the agent REGISTER state transition. Framework-free; delegates to the
 * aggregate factory, which owns the invariants (HTTPS webhook, non-blank name, >=1 category,
 * positive max_execution_seconds, non-negative price). The bean is registered in DomainServiceConfig.
 */
public interface AgentRegisterDomainService {

    AgentModel register(AgentRegisterInfo info);
}
