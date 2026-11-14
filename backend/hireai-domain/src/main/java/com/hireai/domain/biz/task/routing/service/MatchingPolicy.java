package com.hireai.domain.biz.task.routing.service;

import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

/**
 * Immutable weights, exploration rate and relevance floor for the multi-factor matcher. Bound from
 * hireai.matching.* config in DomainServiceConfig; a bad configuration fails bean creation, so a
 * typo in YAML is a startup crash, not a subtly wrong marketplace.
 *
 * <p>{@code relevanceFloor} is τ (ADR 0006): an Agent whose RAW cosine similarity to the task text is
 * below it is ineligible. It is model-specific; see docs/details/matching.md for how it was tuned.
 */
public record MatchingPolicy(double weightReputation, double weightRelevance, double weightValue,
                             double weightLoad, double weightExploration, double epsilon,
                             double relevanceFloor) {

    private static final double TOLERANCE = 1e-9;

    public MatchingPolicy {
        requireUnitRange(weightReputation, "weightReputation");
        requireUnitRange(weightRelevance, "weightRelevance");
        requireUnitRange(weightValue, "weightValue");
        requireUnitRange(weightLoad, "weightLoad");
        requireUnitRange(weightExploration, "weightExploration");
        if (!(epsilon >= 0.0 && epsilon <= 1.0)) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "Matching epsilon must be in [0,1]; got " + epsilon);
        }
        if (!(relevanceFloor >= -1.0 && relevanceFloor <= 1.0)) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "Matching relevance floor must be a cosine similarity in [-1,1]; got " + relevanceFloor);
        }
        double sum = weightReputation + weightRelevance + weightValue + weightLoad + weightExploration;
        if (Math.abs(sum - 1.0) > TOLERANCE) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "Matching weights must sum to 1.0; got " + sum);
        }
    }

    public static MatchingPolicy defaults() {
        return new MatchingPolicy(0.30, 0.25, 0.15, 0.15, 0.15, 0.10, 0.25);
    }

    /** The same policy with another exploration rate (tests pin ε = 0 for a pure argmax). */
    public MatchingPolicy withEpsilon(double newEpsilon) {
        return new MatchingPolicy(weightReputation, weightRelevance, weightValue, weightLoad,
                weightExploration, newEpsilon, relevanceFloor);
    }

    /**
     * Whether a raw cosine similarity reaches τ. A null similarity (the Agent has no vector, or the
     * task text couldn't be embedded) passes: matching never depends on the embedding provider.
     */
    public boolean passesRelevanceFloor(Double similarity) {
        return similarity == null || similarity.isNaN() || similarity >= relevanceFloor;
    }

    private static void requireUnitRange(double value, String name) {
        if (!(value >= 0.0 && value <= 1.0)) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "Matching weight " + name + " must be in [0,1]; got " + value);
        }
    }
}
