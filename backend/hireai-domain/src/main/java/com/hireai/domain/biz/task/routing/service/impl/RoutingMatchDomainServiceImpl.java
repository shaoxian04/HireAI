package com.hireai.domain.biz.task.routing.service.impl;

import com.hireai.domain.biz.offering.agent.info.AgentCandidate;
import com.hireai.domain.biz.task.routing.service.RoutingMatchDomainService;
import com.hireai.domain.biz.task.info.TaskRoutingView;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Stateless, deterministic matcher. Keeps candidates that advertise the task category
 * and whose price does not exceed the budget, then picks the highest reputationScore
 * (tie-break). Returns the chosen agentVersionId, or empty when nothing fits. No
 * framework imports — wired as a bean in DomainServiceConfig.
 */
public class RoutingMatchDomainServiceImpl implements RoutingMatchDomainService {

    @Override
    public Optional<UUID> selectAgentVersion(TaskRoutingView criteria, List<AgentCandidate> candidates) {
        if (criteria == null || candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        return candidates.stream()
                .filter(c -> coversCategory(c, criteria.category()))
                .filter(c -> withinBudget(c, criteria))
                .max(Comparator.comparing(AgentCandidate::reputationScore))
                .map(AgentCandidate::agentVersionId);
    }

    private boolean coversCategory(AgentCandidate candidate, String category) {
        return category != null
                && candidate.capabilityCategories() != null
                && candidate.capabilityCategories().contains(category);
    }

    private boolean withinBudget(AgentCandidate candidate, TaskRoutingView criteria) {
        return criteria.budget() != null
                && candidate.price() != null
                && candidate.price().compareTo(criteria.budget()) <= 0;
    }
}
