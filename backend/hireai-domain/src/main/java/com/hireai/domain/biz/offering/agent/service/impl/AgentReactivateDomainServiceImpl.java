package com.hireai.domain.biz.offering.agent.service.impl;

import com.hireai.domain.biz.offering.agent.model.AgentModel;
import com.hireai.domain.biz.offering.agent.service.AgentReactivateDomainService;

/** Stateless implementation of the reactivate transition; delegates to the aggregate. */
public class AgentReactivateDomainServiceImpl implements AgentReactivateDomainService {
    @Override
    public AgentModel reactivate(AgentModel agent) {
        return agent.reactivate();
    }
}
