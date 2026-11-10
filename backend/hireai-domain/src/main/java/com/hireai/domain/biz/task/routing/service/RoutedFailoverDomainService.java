package com.hireai.domain.biz.task.routing.service;

import com.hireai.domain.biz.offering.agent.info.AgentCandidate;
import com.hireai.domain.biz.task.dispatchattempt.info.DispatchAttemptView;
import com.hireai.domain.biz.task.enums.FailureReason;
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

    /**
     * Why a hop found nobody, once {@link #selectNext} came back empty (#59): {@code NO_ELIGIBLE_AGENT}
     * when an untried Agent exists but the client deadline rules every one out, otherwise
     * {@code NO_ALTERNATIVE_AGENT} (nobody untried is left, or the Agent limit is reached).
     *
     * @param candidatesIgnoringDeadline the same candidate query without the client-deadline bound,
     *                                   or {@code null} when the task has no deadline
     */
    FailureReason exhaustionReason(List<DispatchAttemptView> history,
                                   List<AgentCandidate> candidatesIgnoringDeadline);
}
