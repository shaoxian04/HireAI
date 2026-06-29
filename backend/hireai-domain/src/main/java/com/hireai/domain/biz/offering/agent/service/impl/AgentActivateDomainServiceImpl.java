package com.hireai.domain.biz.offering.agent.service.impl;

import com.hireai.domain.biz.offering.agent.model.AgentModel;
import com.hireai.domain.biz.offering.agent.service.AgentActivateDomainService;

/** Stateless implementation of the activate transition; delegates to the aggregate. */
public class AgentActivateDomainServiceImpl implements AgentActivateDomainService {

    @Override
    public AgentModel activate(AgentModel agent) {
        return agent.activate();
    }
}
