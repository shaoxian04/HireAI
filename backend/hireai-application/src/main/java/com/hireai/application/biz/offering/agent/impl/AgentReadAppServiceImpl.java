package com.hireai.application.biz.offering.agent.impl;

import com.hireai.application.biz.offering.agent.AgentReadAppService;
import com.hireai.utility.result.ResultCode;
import com.hireai.domain.biz.offering.agent.model.AgentModel;
import com.hireai.domain.biz.offering.agent.repository.AgentQuery;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.utility.exception.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentReadAppServiceImpl implements AgentReadAppService {

    private final AgentRepository agentRepository;

    @Override
    public AgentModel getForOwner(UUID agentId, UUID ownerId) {
        AgentModel agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Agent not found: " + agentId));
        if (!agent.ownerId().equals(ownerId)) {
            throw new DomainException(ResultCode.NOT_FOUND, "Agent not found: " + agentId);
        }
        return agent;
    }

    @Override
    public List<AgentModel> listForOwner(UUID ownerId, AgentQuery query) {
        return agentRepository.findByOwnerId(ownerId, query);
    }
}
