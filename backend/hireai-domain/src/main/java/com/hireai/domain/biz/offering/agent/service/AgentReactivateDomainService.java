package com.hireai.domain.biz.offering.agent.service;

import com.hireai.domain.biz.offering.agent.model.AgentModel;

/** Domain service for the agent REACTIVATE transition (SUSPENDED -> ACTIVE). Registered in DomainServiceConfig. */
public interface AgentReactivateDomainService {
    AgentModel reactivate(AgentModel agent);
}
