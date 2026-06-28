package com.hireai.domain.biz.offering.agent.service;

import com.hireai.domain.biz.offering.agent.model.AgentModel;

/** Domain service for the agent DEACTIVATE transition (ACTIVE|SUSPENDED -> DEACTIVATED, terminal).
 *  Registered in DomainServiceConfig. */
public interface AgentDeactivateDomainService {
    AgentModel deactivate(AgentModel agent);
}
