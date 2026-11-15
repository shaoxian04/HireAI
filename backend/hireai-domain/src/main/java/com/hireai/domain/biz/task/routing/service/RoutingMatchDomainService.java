package com.hireai.domain.biz.task.routing.service;

import com.hireai.domain.biz.offering.agent.info.AgentCandidate;
import com.hireai.domain.biz.task.info.MatchCriteria;
import com.hireai.domain.biz.task.routing.info.NoMatchExplanation;
import com.hireai.domain.biz.task.routing.info.ScoredCandidate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The ONE ranking engine for task->agent matching (spec §4). Framework-free; wired in
 * DomainServiceConfig with a MatchingPolicy and an injected RandomGenerator.
 *
 * rank      — deterministic: hard-filter (category + price<=budget + Offer coverage of the criteria's
 *             Contract terms + relevance floor τ on the raw cosine similarity), multi-factor score,
 *             sort best-first, total tie-break (price asc, then agentVersionId asc).
 *             Also the Phase 2 shortlist's top-N source.
 * selectOne — epsilon-greedy over rank: probability 1-epsilon take the top; probability epsilon
 *             sample the eligible set weighted by each candidate's exploration term, so
 *             under-sampled agents occasionally win a real auto-routed job.
 *
 * Exploration randomises SELECTION only — settlement stays deterministic (Hard Invariant #3).
 * Rationale + scenarios: docs/details/matching.md.
 */
public interface RoutingMatchDomainService {

    List<ScoredCandidate> rank(MatchCriteria criteria, List<AgentCandidate> candidates);

    Optional<UUID> selectOne(MatchCriteria criteria, List<AgentCandidate> candidates);

    /**
     * Whether the candidate's raw cosine similarity reaches τ (a candidate without one passes). rank
     * applies it already; the shortlist uses it for the above-budget near-misses it lists itself.
     */
    boolean passesRelevanceFloor(AgentCandidate candidate);

    /**
     * The shortlist's "no close match" state (#85): nothing ranks, yet an affordable candidate was
     * left out only by the relevance floor. Tells the Client the task text matched no Agent closely
     * enough, rather than that no Agent serves the category within budget.
     */
    boolean noCloseMatch(MatchCriteria criteria, List<AgentCandidate> candidates);

    /**
     * Whether the candidate's Offer covers the criteria's Contract terms (spec D34); always true when
     * the criteria carry none. rank applies it already; the shortlist uses it for its near-misses.
     */
    boolean covers(MatchCriteria criteria, AgentCandidate candidate);

    /**
     * Why nothing ranks (spec D35): which Contract parameter, Template version or the relevance floor
     * excluded the Agents that serve the category within budget, with relax suggestions and counts.
     * Empty when something ranks.
     */
    Optional<NoMatchExplanation> explainNoMatch(MatchCriteria criteria, List<AgentCandidate> candidates);
}
