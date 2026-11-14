package com.hireai.domain.biz.task.routing.service.impl;

import com.hireai.domain.biz.offering.agent.info.AgentCandidate;
import com.hireai.domain.biz.task.dispatchattempt.info.DispatchAttemptView;
import com.hireai.domain.biz.task.enums.FailureReason;
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
        Tried tried = Tried.from(history);
        if (tried.limitReached()) {
            return Optional.empty();
        }
        List<AgentCandidate> untried = tried.untried(candidates);
        return matcher.selectOne(criteria, untried)
                .flatMap(versionId -> untried.stream()
                        .filter(c -> c.agentVersionId().equals(versionId))
                        .findFirst());
    }

    @Override
    public FailureReason exhaustionReason(List<DispatchAttemptView> history,
                                          List<AgentCandidate> candidatesIgnoringDeadline) {
        Tried tried = Tried.from(history);
        boolean onlyTheDeadlineStoodInTheWay = candidatesIgnoringDeadline != null
                && !tried.limitReached()
                && !tried.untried(candidatesIgnoringDeadline).isEmpty();
        return onlyTheDeadlineStoodInTheWay ? FailureReason.NO_ELIGIBLE_AGENT : FailureReason.NO_ALTERNATIVE_AGENT;
    }

    /** The Agents and versions a task has already been offered to. */
    private record Tried(Set<UUID> agents, Set<UUID> versions) {

        static Tried from(List<DispatchAttemptView> history) {
            Set<UUID> agents = new HashSet<>();
            Set<UUID> versions = new HashSet<>();
            for (DispatchAttemptView attempt : history) {
                versions.add(attempt.agentVersionId());
                // An attempt whose version row is gone has no agentId; it still counts as one Agent.
                agents.add(attempt.agentId() != null ? attempt.agentId() : attempt.agentVersionId());
            }
            return new Tried(agents, versions);
        }

        boolean limitReached() {
            return agents.size() >= MAX_DISTINCT_AGENTS;
        }

        List<AgentCandidate> untried(List<AgentCandidate> candidates) {
            return candidates.stream()
                    .filter(c -> !agents.contains(c.agentId()) && !versions.contains(c.agentVersionId()))
                    .toList();
        }
    }
}
