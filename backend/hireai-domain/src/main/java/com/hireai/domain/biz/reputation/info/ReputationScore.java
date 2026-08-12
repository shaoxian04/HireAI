package com.hireai.domain.biz.reputation.info;

import java.math.BigDecimal;

/**
 * A scored reputation: the blended {@code score} that steers the matcher, plus the two components
 * it was blended from and the sample counts behind them.
 *
 * <p>The components are carried separately because a single blended number cannot tell a builder
 * whether their agent is <em>failing to deliver</em> or <em>delivering work clients are
 * unimpressed by</em> — two problems with completely different fixes. The builder portal renders
 * both; the client storefront renders neither (showing Satisfaction 73 next to ★4.7 publishes two
 * numbers that look contradictory to anyone who does not know what shrinkage is).
 *
 * @param reliability       platform-witnessed component, in [0,1]
 * @param satisfaction      client-authored component, in [0,1]
 * @param score             100 × the blend, rounded to the 2dp the column stores
 * @param reliabilityCount  outcome samples behind {@code reliability} — the confidence in it
 * @param satisfactionCount rating samples behind {@code satisfaction}
 */
public record ReputationScore(BigDecimal reliability, BigDecimal satisfaction, BigDecimal score,
                              long reliabilityCount, long satisfactionCount) {

    /** True when nothing has been witnessed yet — the agent is unproven, not excellent. */
    public boolean isUnproven() {
        return reliabilityCount == 0 && satisfactionCount == 0;
    }
}
