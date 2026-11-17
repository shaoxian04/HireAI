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
     * The decision after stage {@code stage} (1 or 2) of round {@code round}, from the defects found and the
     * Items sampled so far in this round (both stages together) and the defects in this stage's own sample.
     *
     * <p>A first sample that does not accept the lot always draws the second (D19 step 1), even where ISO
     * 2859-1 would reject on the first sample alone. A double plan judges the second stage on both samples
     * together (Ac2); a single plan judges its second sample, of the same size, on its own (its Ac).
     */
    public static SamplingDecision after(SamplingPlan plan, int stage, int cumulativeDefects, int cumulativeSampled,
                                         int stageDefects, int round) {
        if (round < 1 || round > LAST_ROUND || stage < 1 || stage > 2) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "No sampling stage " + stage + " in round " + round + " under plan " + plan);
        }
        if (stage == 1) {
            return plan.judgeFirst(stageDefects) == StageVerdict.ACCEPT ? acceptLot() : drawSecond();
        }
        StageVerdict verdict = plan.isDouble() ? plan.judgeSecond(cumulativeDefects) : plan.judgeFirst(stageDefects);
        return verdict == StageVerdict.ACCEPT ? acceptLot() : failed(cumulativeDefects, cumulativeSampled, round);
    }

    /**
     * The decision when the first sample left nothing in the lot to draw a second from (it was the whole lot):
     * a double plan judges it against the second stage's Ac2, a single plan against its own Ac.
     */
    public static SamplingDecision afterWholeLot(SamplingPlan plan, int defects, int sampled, int round) {
        if (round < 1 || round > LAST_ROUND) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION, "No sampling round " + round);
        }
        StageVerdict verdict = plan.isDouble() ? plan.judgeSecond(defects) : plan.judgeFirst(defects);
        return verdict == StageVerdict.ACCEPT ? acceptLot() : failed(defects, sampled, round);
    }

    private static SamplingDecision failed(int defects, int sampled, int round) {
        return round < LAST_ROUND ? rework() : proRata(ProRata.errorRate(defects, sampled));
    }
}
