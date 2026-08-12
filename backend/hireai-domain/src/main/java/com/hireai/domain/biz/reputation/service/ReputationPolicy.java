package com.hireai.domain.biz.reputation.service;

import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

/**
 * Immutable tuning constants for the two-component shrinkage reputation model (ADR 0003).
 * Bound from hireai.reputation.* config in DomainServiceConfig; validated here so a bad
 * configuration fails bean creation — a typo in YAML is a startup crash, not a subtly wrong
 * marketplace. Mirrors {@link com.hireai.domain.biz.task.routing.service.MatchingPolicy}.
 *
 * <pre>
 * Reliability  = (kR·p₀ + Σ outcome quality) / (kR + n_outcomes)
 * Satisfaction = (kS·p₀ + Σ rating quality)  / (kS + n_ratings)
 * score        = 100 × (α × Reliability + (1−α) × Satisfaction)
 * </pre>
 *
 * @param alpha                     α — the Reliability share of the blend
 * @param reliabilityPriorStrength  kR — how many neutral samples the prior is worth
 * @param satisfactionPriorStrength kS — deliberately higher than kR: Satisfaction is the
 *                                  forgeable signal and is held to a higher evidential bar
 * @param prior                     p₀ — the neutral quality an unproven agent falls back to
 */
public record ReputationPolicy(double alpha, double reliabilityPriorStrength,
                               double satisfactionPriorStrength, double prior) {

    public ReputationPolicy {
        requireUnitRange(alpha, "alpha");
        requireUnitRange(prior, "prior");
        requirePositive(reliabilityPriorStrength, "reliabilityPriorStrength");
        requirePositive(satisfactionPriorStrength, "satisfactionPriorStrength");
    }

    public static ReputationPolicy defaults() {
        return new ReputationPolicy(0.70, 5.0, 10.0, 0.50);
    }

    private static void requireUnitRange(double value, String name) {
        if (value < 0.0 || value > 1.0) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "Reputation " + name + " must be in [0,1]; got " + value);
        }
    }

    /**
     * Zero would divide by zero for a brand-new agent — the cold-start case that has to land on
     * exactly 50.00.
     */
    private static void requirePositive(double value, String name) {
        if (!(value > 0.0)) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "Reputation " + name + " must be > 0; got " + value);
        }
    }
}
