package com.hireai.domain.biz.offering.agent.service.impl;

import com.hireai.domain.biz.offering.agent.model.AgentModel;
import com.hireai.domain.biz.offering.agent.service.AgentSuspendDomainService;

/** Stateless implementation of the suspend transition; delegates to the aggregate. */
public class AgentSuspendDomainServiceImpl implements AgentSuspendDomainService {
    @Override
    public AgentModel suspend(AgentModel agent) {
        return agent.suspend();
    }
}
