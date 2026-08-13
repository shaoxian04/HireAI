package com.hireai.application.biz.reputation.impl;

import com.hireai.application.biz.reputation.ReputationReadAppService;
import com.hireai.domain.biz.offering.agent.model.AgentModel;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.domain.biz.reputation.info.ReputationAggregates;
import com.hireai.domain.biz.reputation.info.ReputationScore;
import com.hireai.domain.biz.reputation.model.ReputationEventModel;
import com.hireai.domain.biz.reputation.repository.ReputationEventRepository;
import com.hireai.domain.biz.reputation.service.ReputationScoringDomainService;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Thin orchestration: resolve the agent, enforce ownership, read the cached aggregates and the
 * recent stream, and let the domain do the arithmetic. No scoring rule lives here.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReputationReadAppServiceImpl implements ReputationReadAppService {

    private final AgentRepository agentRepository;
    private final ReputationEventRepository reputationEventRepository;
    private final ReputationScoringDomainService scoringDomainService;

    @Override
    public Breakdown getForOwner(UUID agentId, UUID ownerId) {
        // Ownership first, and a foreign agent fails exactly as a missing one does — a builder must
        // not be able to probe for a competitor's agent id (Invariant #5).
        AgentModel agent = agentRepository.findById(agentId)
                .orElseThrow(() -> notFound(agentId));
        if (!agent.ownerId().equals(ownerId)) {
            throw notFound(agentId);
        }

        ReputationAggregates aggregates = agentRepository.findReputationAggregates(agentId)
                .orElseThrow(() -> notFound(agentId));
        ReputationScore score = scoringDomainService.score(aggregates);
        List<ReputationEventModel> recent =
                reputationEventRepository.findRecentByAgentId(agentId, DEFAULT_EVENT_LIMIT);

        return new Breakdown(score, recent);
    }

    private static DomainException notFound(UUID agentId) {
        return new DomainException(ResultCode.NOT_FOUND, "Agent not found: " + agentId);
    }
}
