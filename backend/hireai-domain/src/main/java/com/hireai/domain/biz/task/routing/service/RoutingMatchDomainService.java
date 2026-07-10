package com.hireai.domain.biz.task.routing.service;

import com.hireai.domain.biz.offering.agent.info.AgentCandidate;
import com.hireai.domain.biz.task.info.MatchCriteria;
import com.hireai.domain.biz.task.routing.info.ScoredCandidate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The ONE ranking engine for task->agent matching (spec §4). Framework-free; wired in
 * DomainServiceConfig with a MatchingPolicy and an injected RandomGenerator.
 *
 * rank      — deterministic: hard-filter (category + price<=budget), multi-factor score,
 *             sort best-first, total tie-break (price asc, then agentVersionId asc).
 *             Also the Phase 2 shortlist's top-N source.
 * selectOne — epsilon-greedy over rank: probability 1-epsilon take the top; probability epsilon
 *             sample the eligible set weighted by each candidate's exploration term, so
 *             under-sampled agents occasionally win a real auto-routed job.
 *
 * Exploration randomises SELECTION only — settlement stays deterministic (Hard Invariant #3).
 * Rationale + scenarios: docs/matching-selection-mechanics.md.
 */
public interface RoutingMatchDomainService {

    List<ScoredCandidate> rank(MatchCriteria criteria, List<AgentCandidate> candidates);

    Optional<UUID> selectOne(MatchCriteria criteria, List<AgentCandidate> candidates);
}
