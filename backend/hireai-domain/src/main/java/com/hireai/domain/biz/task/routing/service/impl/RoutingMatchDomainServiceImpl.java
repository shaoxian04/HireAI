package com.hireai.domain.biz.task.routing.service.impl;

import com.hireai.domain.biz.offering.agent.info.AgentCandidate;
import com.hireai.domain.biz.task.info.MatchCriteria;
import com.hireai.domain.biz.task.routing.info.ScoredCandidate;
import com.hireai.domain.biz.task.routing.service.MatchingPolicy;
import com.hireai.domain.biz.task.routing.service.RoutingMatchDomainService;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Multi-factor scorer + epsilon-greedy selector. Stateless apart from the injected RandomGenerator
 * (seedable in tests; with epsilon=0 selectOne is a pure argmax). Every factor is normalised to [0,1]
 * so the configured weights keep their meaning:
 *   reputation/100 ; relevance (below) ; (budget-price)/budget ; max(0, 1 - inFlight/maxConcurrent) ;
 *   1/(1+sampleCount)
 * loadHeadroom is a SOFT factor: an at/over-capacity agent scores 0 on it but stays eligible.
 *
 * <p>Relevance (#85, ADR 0006). The candidate carries the RAW cosine similarity between the task text
 * and its profile. A raw value below τ makes it ineligible (the floor never uses the normalised value).
 * Among the eligible candidates that have a similarity, it is min-max normalised (best 1, worst 0); with
 * one such candidate, or all tied, each scores 0.5. A candidate without a similarity (no Agent vector,
 * or no task vector at all) passes the floor and scores a neutral 0.5, so without a task vector the
 * other four factors decide.
 */
public class RoutingMatchDomainServiceImpl implements RoutingMatchDomainService {

    /** Neutral relevance for an Agent without a vector, a lone candidate, or a tie. */
    static final double NEUTRAL_RELEVANCE = 0.5;
    /** Similarity spreads below this are float noise, treated as a tie. */
    private static final double TIE_TOLERANCE = 1e-6;

    private final MatchingPolicy policy;
    private final RandomGenerator rng;

    public RoutingMatchDomainServiceImpl(MatchingPolicy policy, RandomGenerator rng) {
        this.policy = policy;
        this.rng = rng;
    }

    @Override
    public List<ScoredCandidate> rank(MatchCriteria criteria, List<AgentCandidate> candidates) {
        if (criteria == null || candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<AgentCandidate> eligible = candidates.stream()
                .filter(c -> coversCategory(c, criteria.category()))
                .filter(c -> withinBudget(c, criteria))
                .filter(this::passesRelevanceFloor)
                .toList();
        RelevanceRange range = RelevanceRange.of(eligible);
        return eligible.stream()
                .map(c -> new ScoredCandidate(c, score(c, criteria, range)))
                .sorted(Comparator.comparingDouble(ScoredCandidate::score).reversed()
                        .thenComparing(sc -> sc.candidate().price())
                        .thenComparing(sc -> sc.candidate().agentVersionId()))
                .toList();
    }

    @Override
    public Optional<UUID> selectOne(MatchCriteria criteria, List<AgentCandidate> candidates) {
        List<ScoredCandidate> ranked = rank(criteria, candidates);
        if (ranked.isEmpty()) {
            return Optional.empty();
        }
        if (ranked.size() > 1 && rng.nextDouble() < policy.epsilon()) {
            return Optional.of(sampleByExploration(ranked).candidate().agentVersionId());
        }
        return Optional.of(ranked.get(0).candidate().agentVersionId());
    }

    @Override
    public boolean passesRelevanceFloor(AgentCandidate candidate) {
        return policy.passesRelevanceFloor(similarityOf(candidate));
    }

    private double score(AgentCandidate c, MatchCriteria criteria, RelevanceRange range) {
        double reputation = c.reputationScore() == null
                ? 0.0 : clamp(c.reputationScore().doubleValue() / 100.0);
        double budget = criteria.budget().doubleValue();
        double valueFit = budget <= 0.0
                ? 0.0 : clamp((budget - c.price().doubleValue()) / budget);
        double loadHeadroom = c.maxConcurrent() <= 0
                ? 0.0 : clamp(1.0 - (double) c.inFlight() / c.maxConcurrent());
        double exploration = explorationTerm(c);
        return policy.weightReputation() * reputation
                + policy.weightValue() * valueFit
                + policy.weightLoad() * loadHeadroom
                + policy.weightExploration() * exploration
                + policy.weightRelevance() * range.normalise(similarityOf(c));
    }

    /** A usable similarity, or null (missing, or NaN from a zero vector). */
    private static Double similarityOf(AgentCandidate c) {
        Double s = c.similarity();
        return s == null || s.isNaN() ? null : s;
    }

    /** Min and max raw similarity over the eligible candidates that have one. */
    private record RelevanceRange(double min, double max, boolean empty) {

        static RelevanceRange of(List<AgentCandidate> eligible) {
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (AgentCandidate c : eligible) {
                Double s = similarityOf(c);
                if (s != null) {
                    min = Math.min(min, s);
                    max = Math.max(max, s);
                }
            }
            return new RelevanceRange(min, max, min > max);
        }

        double normalise(Double similarity) {
            if (similarity == null || empty || max - min < TIE_TOLERANCE) {
                return NEUTRAL_RELEVANCE;
            }
            return clamp((similarity - min) / (max - min));
        }
    }

    /** Weighted lottery over the eligible set; ticket size = the exploration term (spec §4.3). */
    private ScoredCandidate sampleByExploration(List<ScoredCandidate> ranked) {
        double total = 0.0;
        for (ScoredCandidate sc : ranked) {
            total += explorationTerm(sc.candidate());
        }
        double threshold = rng.nextDouble() * total;
        double cumulative = 0.0;
        for (ScoredCandidate sc : ranked) {
            cumulative += explorationTerm(sc.candidate());
            if (threshold < cumulative) {
                return sc;
            }
        }
        return ranked.get(ranked.size() - 1);
    }

    private static double explorationTerm(AgentCandidate c) {
        return 1.0 / (1.0 + c.sampleCount());
    }

    private static double clamp(double value) {
        return Math.min(1.0, Math.max(0.0, value));
    }

    private boolean coversCategory(AgentCandidate candidate, String category) {
        return category != null
                && candidate.capabilityCategories() != null
                && candidate.capabilityCategories().contains(category);
    }

    private boolean withinBudget(AgentCandidate candidate, MatchCriteria criteria) {
        return criteria.budget() != null
                && candidate.price() != null
                && candidate.price().compareTo(criteria.budget()) <= 0;
    }
}
