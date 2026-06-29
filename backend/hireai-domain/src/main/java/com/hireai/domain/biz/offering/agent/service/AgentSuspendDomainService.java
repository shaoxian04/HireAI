package com.hireai.domain.biz.offering.agent.service;

import com.hireai.domain.biz.offering.agent.model.AgentModel;

/** Domain service for the agent SUSPEND transition (ACTIVE -> SUSPENDED). Framework-free;
 *  delegates to the aggregate's guarded transition. Registered in DomainServiceConfig. */
public interface AgentSuspendDomainService {
    AgentModel suspend(AgentModel agent);
}
