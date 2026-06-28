package com.hireai.application.biz.offering.agent.impl;

import com.hireai.application.biz.offering.agent.AgentWriteAppService;
import com.hireai.utility.result.ResultCode;
import com.hireai.domain.biz.offering.agent.event.AgentActivatedDomainEvent;
import com.hireai.domain.biz.offering.agent.event.AgentRegisteredDomainEvent;
import com.hireai.domain.biz.offering.agent.info.AgentRegisterInfo;
import com.hireai.domain.biz.offering.agent.info.PublishVersionInfo;
import com.hireai.domain.biz.offering.agent.model.AgentModel;
import com.hireai.domain.biz.offering.agent.model.AgentProfileModel;
import com.hireai.domain.biz.offering.agent.model.Pricing;
import com.hireai.domain.biz.offering.agent.repository.AgentProfileRepository;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.domain.biz.offering.agent.service.AgentActivateDomainService;
import com.hireai.domain.biz.offering.agent.service.AgentRegisterDomainService;
import com.hireai.utility.exception.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class AgentWriteAppServiceImpl implements AgentWriteAppService {

    private final AgentRepository agentRepository;
    private final AgentProfileRepository agentProfileRepository;
    private final AgentRegisterDomainService registerDomainService;
    private final AgentActivateDomainService activateDomainService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public UUID register(AgentRegisterInfo registerInfo) {
        AgentModel agent = registerDomainService.register(registerInfo);
        UUID agentId = agentRepository.save(agent).id();
        agentProfileRepository.save(AgentProfileModel.createDefault(agentId));
        eventPublisher.publishEvent(new AgentRegisteredDomainEvent(
                agentId, agent.ownerId(), agent.currentVersion().id(), Instant.now()));
        log.info("Agent {} registered by owner {} (PENDING_VERIFICATION)", agentId, agent.ownerId());
        return agentId;
    }

    @Override
    public void activate(UUID agentId, UUID ownerId) {
        AgentModel agent = loadOwned(agentId, ownerId);
        AgentModel active = activateDomainService.activate(agent);
        agentRepository.save(active);
        eventPublisher.publishEvent(new AgentActivatedDomainEvent(
                active.id(), active.ownerId(), active.currentVersionId(), Instant.now()));
        log.info("Agent {} activated by owner {}", agentId, ownerId);
    }

    /**
     * Publish-new-version. Pricing edits are permitted on PENDING_VERIFICATION agents too — the
     * version model exists from registration, so currentVersion() is always non-null after register().
     */
    @Override
    public AgentModel publishNewVersion(UUID agentId, UUID ownerId, PublishVersionInfo info) {
        AgentModel agent = loadOwned(agentId, ownerId);
        AgentModel updated = agent.publishNewVersion(Pricing.of(info.price()),
                info.maxExecutionSeconds(), info.capabilityCategories());
        agentRepository.publishNewVersion(updated);
        log.info("Agent {} published version {} by owner {} (price={}, maxExec={})",
                agentId, updated.currentVersion().versionNumber(), ownerId,
                info.price(), info.maxExecutionSeconds());
        return agentRepository.findById(agentId)
                .orElseThrow(() -> new DomainException(ResultCode.INTERNAL_ERROR,
                        "Agent disappeared after publishing a version: " + agentId));
    }

    /** Load + owner check (Invariant #5): a foreign agent is indistinguishable from a missing one. */
    private AgentModel loadOwned(UUID agentId, UUID ownerId) {
        AgentModel agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Agent not found: " + agentId));
        if (!agent.ownerId().equals(ownerId)) {
            throw new DomainException(ResultCode.NOT_FOUND, "Agent not found: " + agentId);
        }
        return agent;
    }
}
