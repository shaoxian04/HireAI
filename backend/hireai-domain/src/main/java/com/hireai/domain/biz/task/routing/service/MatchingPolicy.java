package com.hireai.domain.biz.task.routing.service;

import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

/**
 * Immutable weights + exploration rate for the multi-factor matcher (spec §4.2). Bound from
 * hireai.matching.* config in DomainServiceConfig; a bad configuration fails bean creation,
 * so a typo in YAML is a startup crash, not a subtly wrong marketplace.
 */
public record MatchingPolicy(double weightReputation, double weightValue, double weightLoad,
                             double weightExploration, double epsilon) {

    private static final double TOLERANCE = 1e-9;

    public MatchingPolicy {
        requireUnitRange(weightReputation, "weightReputation");
        requireUnitRange(weightValue, "weightValue");
        requireUnitRange(weightLoad, "weightLoad");
        requireUnitRange(weightExploration, "weightExploration");
        if (epsilon < 0.0 || epsilon > 1.0) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "Matching epsilon must be in [0,1]; got " + epsilon);
        }
        double sum = weightReputation + weightValue + weightLoad + weightExploration;
        if (Math.abs(sum - 1.0) > TOLERANCE) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "Matching weights must sum to 1.0; got " + sum);
        }
    }

    public static MatchingPolicy defaults() {
        return new MatchingPolicy(0.40, 0.20, 0.20, 0.20, 0.10);
    }

    private static void requireUnitRange(double value, String name) {
        if (value < 0.0 || value > 1.0) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "Matching weight " + name + " must be in [0,1]; got " + value);
        }
    }
}
