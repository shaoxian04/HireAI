package com.hireai.domain.biz.task.sampling.model;

import com.hireai.domain.biz.task.sampling.enums.StageVerdict;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.math.BigDecimal;

/**
 * What happens once a sample has been judged (spec D19):
 * <ol>
 *   <li>the lot passes: every passing Item still awaiting review is accepted;</li>
 *   <li>the first sample is inconclusive: the second sample is drawn;</li>
 *   <li>the lot fails in round 1: the whole Task is reworked once and resampled (round 2);</li>
 *   <li>the lot fails in round 2: it settles pro rata at the round's measured error rate.</li>
 * </ol>
 * {@code errorRate} is set for {@link Next#PRO_RATA} only.
 */
public record SamplingDecision(Next next, BigDecimal errorRate) {

    /** The round after the whole-Task rework; there is no third. */
    public static final int LAST_ROUND = 2;

    public enum Next {
        ACCEPT_LOT,
        DRAW_SECOND,
        REWORK,
        PRO_RATA
    }

    public static SamplingDecision acceptLot() {
        return new SamplingDecision(Next.ACCEPT_LOT, null);
    }

    public static SamplingDecision drawSecond() {
        return new SamplingDecision(Next.DRAW_SECOND, null);
    }

    public static SamplingDecision rework() {
        return new SamplingDecision(Next.REWORK, null);
    }

    public static SamplingDecision proRata(BigDecimal errorRate) {
        return new SamplingDecision(Next.PRO_RATA, errorRate);
    }

    /**
     * The decision after stage {@code stage} (1 or 2) of round {@code round}, from the defects found
     * and the Items sampled so far in this round (both stages together).
     */
    public static SamplingDecision after(SamplingPlan plan, int stage, int cumulativeDefects, int cumulativeSampled,
                                         int round) {
        if (round < 1 || round > LAST_ROUND || stage < 1 || stage > 2 || (stage == 2 && !plan.isDouble())) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "No sampling stage " + stage + " in round " + round + " under plan " + plan);
        }
        StageVerdict verdict = stage == 1 ? plan.judgeFirst(cumulativeDefects) : plan.judgeSecond(cumulativeDefects);
        return switch (verdict) {
            case ACCEPT -> acceptLot();
            case SECOND_SAMPLE -> drawSecond();
            case REJECT -> round < LAST_ROUND ? rework()
                    : proRata(ProRata.errorRate(cumulativeDefects, cumulativeSampled));
        };
    }
}
