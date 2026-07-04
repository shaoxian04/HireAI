package com.hireai.domain.biz.offering.agent.service.impl;

import com.hireai.domain.biz.offering.agent.info.AgentRegisterInfo;
import com.hireai.domain.biz.offering.agent.model.AgentModel;
import com.hireai.domain.biz.offering.agent.model.Pricing;
import com.hireai.domain.biz.offering.agent.service.AgentRegisterDomainService;

/** Stateless implementation of the register transition; delegates to the aggregate factory. */
public class AgentRegisterDomainServiceImpl implements AgentRegisterDomainService {

    @Override
    public AgentModel register(AgentRegisterInfo info) {
        return AgentModel.register(info.ownerId(), info.name(), info.outputSpec(),
                info.capabilityCategories(), info.webhookUrl(), info.maxExecutionSeconds(),
                Pricing.of(info.price()), info.maxConcurrent());
    }
}
