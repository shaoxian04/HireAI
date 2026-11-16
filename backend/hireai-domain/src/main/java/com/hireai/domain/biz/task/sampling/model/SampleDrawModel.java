package com.hireai.domain.biz.task.sampling.model;

import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * One drawn sample (spec S8, D6): stage 1 or 2 of round 1 or 2, the plan it was drawn under, the seed
 * it was drawn from and the Item numbers it holds. Recorded when drawn and never redrawn; once every
 * sampled Item settled, the defects found and the decision taken are recorded on it once.
 */
public record SampleDrawModel(UUID id, UUID taskId, int round, int stage, long seed, SamplingPlan plan,
                              List<Integer> itemNos, Instant drawnAt, Integer defects,
                              SamplingDecision.Next decision, Instant decidedAt) {

    public SampleDrawModel {
        itemNos = List.copyOf(itemNos);
    }

    /** Draws stage {@code stage} of round {@code round} from {@code candidates} with {@code seed}. */
    public static SampleDrawModel draw(UUID taskId, int round, int stage, SamplingPlan plan, long seed,
                                       Collection<Integer> candidates, Instant now) {
        int size = stage == 1 ? plan.firstSampleSize() : plan.secondSampleSize();
        List<Integer> drawn = SampleDraw.draw(seed, candidates, size);
        if (drawn.isEmpty()) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Task " + taskId + ": nothing left to sample for stage " + stage);
        }
        return new SampleDrawModel(UUID.randomUUID(), taskId, round, stage, seed, plan, drawn, now,
                null, null, null);
    }

    public boolean isDecided() {
        return decision != null;
    }

    public boolean contains(int itemNo) {
        return itemNos.contains(itemNo);
    }

    public SampleDrawModel decide(int foundDefects, SamplingDecision.Next next, Instant now) {
        if (isDecided()) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Sample " + id + " of task " + taskId + " was already decided (" + decision + ")");
        }
        return new SampleDrawModel(id, taskId, round, stage, seed, plan, itemNos, drawnAt, foundDefects, next, now);
    }
}
