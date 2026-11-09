package com.hireai.domain.biz.task.routing.service.impl;

import com.hireai.domain.biz.offering.agent.info.AgentCandidate;
import com.hireai.domain.biz.task.dispatchattempt.info.DispatchAttemptView;
import com.hireai.domain.biz.task.info.MatchCriteria;
import com.hireai.domain.biz.task.routing.service.RoutedFailoverDomainService;
import com.hireai.domain.biz.task.routing.service.RoutingMatchDomainService;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class RoutedFailoverDomainServiceImpl implements RoutedFailoverDomainService {

    private final RoutingMatchDomainService matcher;

    public RoutedFailoverDomainServiceImpl(RoutingMatchDomainService matcher) {
        this.matcher = Objects.requireNonNull(matcher, "matcher");
    }

    @Override
    public Optional<AgentCandidate> selectNext(MatchCriteria criteria, List<DispatchAttemptView> history,
                                               List<AgentCandidate> candidates) {
        Set<UUID> triedAgents = new HashSet<>();
        Set<UUID> triedVersions = new HashSet<>();
        for (DispatchAttemptView attempt : history) {
            triedVersions.add(attempt.agentVersionId());
            // An attempt whose version row is gone has no agentId; it still counts as one Agent.
            triedAgents.add(attempt.agentId() != null ? attempt.agentId() : attempt.agentVersionId());
        }
        if (triedAgents.size() >= MAX_DISTINCT_AGENTS) {
            return Optional.empty();
        }
        List<AgentCandidate> untried = candidates.stream()
                .filter(c -> !triedAgents.contains(c.agentId()) && !triedVersions.contains(c.agentVersionId()))
                .toList();
        return matcher.selectOne(criteria, untried)
                .flatMap(versionId -> untried.stream()
                        .filter(c -> c.agentVersionId().equals(versionId))
                        .findFirst());
    }
}
