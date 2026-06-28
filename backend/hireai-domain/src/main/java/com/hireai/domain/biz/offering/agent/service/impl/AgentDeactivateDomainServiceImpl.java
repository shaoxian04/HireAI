package com.hireai.domain.biz.offering.agent.service.impl;

import com.hireai.domain.biz.offering.agent.model.AgentModel;
import com.hireai.domain.biz.offering.agent.service.AgentDeactivateDomainService;

/** Stateless implementation of the deactivate transition; delegates to the aggregate. */
public class AgentDeactivateDomainServiceImpl implements AgentDeactivateDomainService {
    @Override
    public AgentModel deactivate(AgentModel agent) {
        return agent.deactivate();
    }
}
