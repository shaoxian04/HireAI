package com.hireai.domain.biz.reputation.service.impl;

import com.hireai.domain.biz.reputation.info.ReputationAggregates;
import com.hireai.domain.biz.reputation.info.ReputationScore;
import com.hireai.domain.biz.reputation.service.ReputationPolicy;
import com.hireai.domain.biz.reputation.service.ReputationScoringDomainService;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The two-component shrinkage model (ADR 0003 / design §5).
 *
 * <p>Shrinkage rather than a points balance: a balance saturates at the top of the market and its
 * ranking becomes an artifact of the tuning constants. Here, count enters only through the
 * denominator — volume moves an agent toward its true quality rate and can never carry it past
 * that rate.
 */
public class ReputationScoringDomainServiceImpl implements ReputationScoringDomainService {

    /** Intermediate division scale; the components are only ever displayed to 3dp. */
    private static final int COMPONENT_SCALE = 6;
    /** reputation_score is NUMERIC(5,2). */
    private static final int SCORE_SCALE = 2;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final ReputationPolicy policy;

    public ReputationScoringDomainServiceImpl(ReputationPolicy policy) {
        this.policy = policy;
    }

    @Override
    public ReputationScore score(ReputationAggregates aggregates) {
        BigDecimal reliability = shrink(aggregates.reliabilitySum(), aggregates.reliabilityCount(),
                policy.reliabilityPriorStrength());
        BigDecimal satisfaction = shrink(aggregates.satisfactionSum(), aggregates.satisfactionCount(),
                policy.satisfactionPriorStrength());

        BigDecimal alpha = BigDecimal.valueOf(policy.alpha());
        BigDecimal blended = alpha.multiply(reliability)
                .add(BigDecimal.ONE.subtract(alpha).multiply(satisfaction));

        return new ReputationScore(
                reliability,
                satisfaction,
                HUNDRED.multiply(blended).setScale(SCORE_SCALE, RoundingMode.HALF_UP),
                aggregates.reliabilityCount(),
                aggregates.satisfactionCount());
    }

    /**
     * (k·p₀ + Σq) / (k + n). With no samples this is exactly p₀, which is what makes a zero-event
     * agent score exactly 50.00 — identical to the DEFAULT_REPUTATION written since V3, so nothing
     * shifts on migration day.
     */
    private BigDecimal shrink(BigDecimal sum, long count, double priorStrength) {
        BigDecimal k = BigDecimal.valueOf(priorStrength);
        BigDecimal numerator = k.multiply(BigDecimal.valueOf(policy.prior())).add(sum);
        BigDecimal denominator = k.add(BigDecimal.valueOf(count));
        return numerator.divide(denominator, COMPONENT_SCALE, RoundingMode.HALF_UP);
    }
}
