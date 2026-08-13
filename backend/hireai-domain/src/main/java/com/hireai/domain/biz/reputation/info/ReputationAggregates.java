package com.hireai.domain.biz.reputation.info;

import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.math.BigDecimal;

/**
 * The running per-agent totals the score is derived from — summed quality and sample count for
 * each component. Persisted on the agents row so a settlement updates the score in constant time
 * instead of re-reading the agent's whole event history.
 *
 * <p>These are a <strong>derived cache</strong>: {@code reputation_events} remains the source of
 * truth (Invariant #2), and the reconciliation path replays the stream and asserts these agree.
 */
public record ReputationAggregates(BigDecimal reliabilitySum, long reliabilityCount,
                                   BigDecimal satisfactionSum, long satisfactionCount) {

    public ReputationAggregates {
        reliabilitySum = requireNonNegative(reliabilitySum, "reliabilitySum");
        satisfactionSum = requireNonNegative(satisfactionSum, "satisfactionSum");
        requireNonNegative(reliabilityCount, "reliabilityCount");
        requireNonNegative(satisfactionCount, "satisfactionCount");
    }

    /** A brand-new agent: no events of either kind, so both components fall back to the prior. */
    public static ReputationAggregates empty() {
        return new ReputationAggregates(BigDecimal.ZERO, 0L, BigDecimal.ZERO, 0L);
    }

    private static BigDecimal requireNonNegative(BigDecimal value, String name) {
        if (value == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, name + " is required");
        }
        if (value.signum() < 0) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    name + " must be >= 0; got " + value);
        }
        return value;
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    name + " must be >= 0; got " + value);
        }
    }
}
