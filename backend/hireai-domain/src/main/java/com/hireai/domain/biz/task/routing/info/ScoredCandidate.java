package com.hireai.domain.biz.task.routing.info;

import com.hireai.domain.biz.offering.agent.info.AgentCandidate;

/**
 * One ranked matching candidate with its computed multi-factor score. Returned by
 * RoutingMatchDomainService.rank (best-first); the Phase 2 shortlist reads the top N,
 * selectOne applies epsilon-greedy on top.
 */
public record ScoredCandidate(AgentCandidate candidate, double score) {
}
