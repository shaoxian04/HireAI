package com.hireai.application.biz.acceptance.engagement.impl;

import com.hireai.application.biz.acceptance.TemplateReadAppService;
import com.hireai.application.biz.acceptance.engagement.EngagementAppService;
import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.engagement.model.EngagementModel;
import com.hireai.domain.biz.acceptance.engagement.repository.EngagementRepository;
import com.hireai.domain.biz.offering.agent.model.AgentModel;
import com.hireai.domain.biz.offering.agent.repository.AgentQuery;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.domain.biz.offering.storefront.model.StorefrontModel;
import com.hireai.domain.biz.offering.storefront.repository.StorefrontRepository;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class EngagementAppServiceImpl implements EngagementAppService {

    private final EngagementRepository engagementRepository;
    private final AgentRepository agentRepository;
    private final StorefrontRepository storefrontRepository;
    private final TemplateReadAppService templateReadAppService;
    private final Clock clock;
    /** The longest (and default) document retention after the last window closes (D32, D41). */
    private final int retentionDays;

    public EngagementAppServiceImpl(EngagementRepository engagementRepository, AgentRepository agentRepository,
                                    StorefrontRepository storefrontRepository,
                                    TemplateReadAppService templateReadAppService, Clock clock,
                                    @org.springframework.beans.factory.annotation.Value(
                                            "${hireai.source.retention-days:30}") int retentionDays) {
        this.engagementRepository = engagementRepository;
        this.agentRepository = agentRepository;
        this.storefrontRepository = storefrontRepository;
        this.templateReadAppService = templateReadAppService;
        this.clock = clock;
        this.retentionDays = retentionDays;
    }

    @Override
    @Transactional
    public EngagementModel open(UUID clientId, UUID agentId, boolean confidentialConsent, Contract templateVariant,
                                Integer retentionDays) {
        // The buyer chooses the Agent as it would book it: ACTIVE and listed, else it is not found.
        AgentModel agent = agentRepository.findById(agentId).orElseThrow(() -> notFound("Agent not found"));
        boolean listed = storefrontRepository.findByAgentId(agentId).map(StorefrontModel::listed).orElse(false);
        if (!agent.isActive() || !listed) {
            throw notFound("Agent not found");
        }
        if (templateVariant != null) {
            templateReadAppService.requireValid(templateVariant);
        }
        EngagementModel engagement = EngagementModel.open(clientId, agentId, confidentialConsent, templateVariant,
                retentionDays, this.retentionDays, clock.instant());
        engagementRepository.insert(engagement);
        log.info("Engagement {} opened by client {} with agent {} ({})", engagement.id(), clientId, agentId,
                engagement.status());
        return engagement;
    }

    @Override
    @Transactional
    public EngagementModel acceptVariant(UUID engagementId, UUID builderId) {
        EngagementModel engagement = engagementRepository.findById(engagementId)
                .filter(e -> ownsAgent(builderId, e.agentId()))
                .orElseThrow(() -> notFound("Engagement not found"));
        EngagementModel accepted = engagement.acceptVariant(clock.instant());
        engagementRepository.update(accepted);
        return accepted;
    }

    @Override
    @Transactional
    public EngagementModel end(UUID engagementId, UUID clientId) {
        EngagementModel ended = getForClient(engagementId, clientId).end(clock.instant());
        engagementRepository.update(ended);
        return ended;
    }

    @Override
    @Transactional(readOnly = true)
    public EngagementModel getForParticipant(UUID engagementId, UUID userId) {
        return engagementRepository.findById(engagementId)
                .filter(e -> e.isOwnedBy(userId) || ownsAgent(userId, e.agentId()))
                .orElseThrow(() -> notFound("Engagement not found"));
    }

    @Override
    @Transactional(readOnly = true)
    public EngagementModel getForClient(UUID engagementId, UUID clientId) {
        return engagementRepository.findById(engagementId)
                .filter(e -> e.isOwnedBy(clientId))
                .orElseThrow(() -> notFound("Engagement not found"));
    }

    @Override
    @Transactional(readOnly = true)
    public List<EngagementModel> listForClient(UUID clientId) {
        return engagementRepository.findByClientId(clientId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<EngagementModel> listForBuilder(UUID builderId) {
        List<UUID> agentIds = agentRepository.findByOwnerId(builderId, new AgentQuery(0, 100)).stream()
                .map(AgentModel::id).toList();
        return engagementRepository.findByAgentIds(agentIds);
    }

    private boolean ownsAgent(UUID userId, UUID agentId) {
        return agentRepository.findById(agentId).map(a -> a.ownerId().equals(userId)).orElse(false);
    }

    private static DomainException notFound(String message) {
        return new DomainException(ResultCode.NOT_FOUND, message);
    }
}
