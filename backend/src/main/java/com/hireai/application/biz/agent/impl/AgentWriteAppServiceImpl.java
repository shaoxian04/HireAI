package com.hireai.application.biz.agent.impl;

import com.hireai.application.biz.agent.AgentWriteAppService;
import com.hireai.controller.base.ResultCode;
import com.hireai.domain.biz.agent.event.AgentActivatedDomainEvent;
import com.hireai.domain.biz.agent.event.AgentRegisteredDomainEvent;
import com.hireai.domain.biz.agent.info.AgentRegisterInfo;
import com.hireai.domain.biz.agent.info.PricingUpdateInfo;
import com.hireai.domain.biz.agent.model.AgentModel;
import com.hireai.domain.biz.agent.model.AgentProfileModel;
import com.hireai.domain.biz.agent.model.AgentVersionModel;
import com.hireai.domain.biz.agent.model.Pricing;
import com.hireai.domain.biz.agent.repository.AgentProfileRepository;
import com.hireai.domain.biz.agent.repository.AgentRepository;
import com.hireai.domain.biz.agent.service.AgentActivateDomainService;
import com.hireai.domain.biz.agent.service.AgentRegisterDomainService;
import com.hireai.domain.shared.exception.DomainException;
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
        AgentModel agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Agent not found: " + agentId));
        if (!agent.ownerId().equals(ownerId)) {
            throw new DomainException(ResultCode.NOT_FOUND, "Agent not found: " + agentId);
        }
        AgentModel active = activateDomainService.activate(agent);
        agentRepository.save(active);
        eventPublisher.publishEvent(new AgentActivatedDomainEvent(
                active.id(), active.ownerId(), active.currentVersionId(), Instant.now()));
        log.info("Agent {} activated by owner {}", agentId, ownerId);
    }

    /**
     * In-place commercial update for the current version (spec §9 — no version history).
     * Mirrors activate()'s ownership check exactly: load agent, compare ownerId, throw
     * NOT_FOUND on mismatch (existence not leaked).
     *
     * Note: pricing edits are permitted even for PENDING_VERIFICATION agents — the version
     * model exists from registration regardless of activation status, so currentVersion()
     * is always non-null after register().
     */
    @Override
    public AgentModel updatePricing(UUID agentId, UUID ownerId, PricingUpdateInfo info) {
        AgentModel agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Agent not found: " + agentId));
        if (!agent.ownerId().equals(ownerId)) {
            throw new DomainException(ResultCode.NOT_FOUND, "Agent not found: " + agentId);
        }
        AgentVersionModel updated = agent.currentVersion()
                .updateCommercials(Pricing.of(info.price()), info.maxExecutionSeconds(),
                        info.capabilityCategories());
        agentRepository.updateCurrentVersion(updated);
        log.info("Agent {} pricing updated by owner {} (price={}, maxExec={})",
                agentId, ownerId, info.price(), info.maxExecutionSeconds());
        return agentRepository.findById(agentId)
                .orElseThrow(() -> new DomainException(ResultCode.INTERNAL_ERROR,
                        "Agent disappeared after pricing update: " + agentId));
    }
}
