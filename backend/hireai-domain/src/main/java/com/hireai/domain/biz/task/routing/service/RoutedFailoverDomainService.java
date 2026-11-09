package com.hireai.domain.biz.task.routing.service;

import com.hireai.domain.biz.offering.agent.info.AgentCandidate;
import com.hireai.domain.biz.task.dispatchattempt.info.DispatchAttemptView;
import com.hireai.domain.biz.task.info.MatchCriteria;

import java.util.List;
import java.util.Optional;

/**
 * Routed failover (#78): picks the next Agent for a task the platform routed, after the assigned
 * Agent could not take it (unreachable after the retry schedule, or busy). Framework-free; wired
 * in DomainServiceConfig.
 *
 * <p>The rules: every Agent already tried for the task (any outcome, any of its versions) is
 * excluded; at most {@link #MAX_DISTINCT_AGENTS} distinct Agents are ever tried; the remaining
 * candidates go through the same matcher as initial routing, so the budget and eligibility
 * filters are identical. Empty means there is no alternative: the caller ends the task with
 * {@code NO_ALTERNATIVE_AGENT}.
 */
public interface RoutedFailoverDomainService {

    /** The most distinct Agents one routed task is ever offered to. */
    int MAX_DISTINCT_AGENTS = 3;

    /**
     * @param criteria   the task's category and budget
     * @param history    the task's dispatch attempts, INCLUDING the one that just failed
     * @param candidates the currently eligible candidates (the initial-matching query)
     */
    Optional<AgentCandidate> selectNext(MatchCriteria criteria, List<DispatchAttemptView> history,
                                        List<AgentCandidate> candidates);
}
