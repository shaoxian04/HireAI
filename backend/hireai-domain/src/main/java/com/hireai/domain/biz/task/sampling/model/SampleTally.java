package com.hireai.domain.biz.task.sampling.model;

import com.hireai.domain.biz.task.sampling.enums.SampleItemOutcome;

import java.util.Collection;
import java.util.Map;

/**
 * The defects found in a sample (D19). Only the sampled Items count: an Item the reviewer checked
 * outside the sample is handled on its own and never feeds the error rate. {@code complete} is false
 * while any sampled Item is still open, so a sample is judged on its judgements as they stand after
 * every rejection response path settled (D17′).
 */
public record SampleTally(int sampled, int defects, boolean complete) {

    public static SampleTally of(Collection<Integer> sampledItemNos, Map<Integer, SampleItemOutcome> outcomes) {
        int defects = 0;
        boolean complete = true;
        for (Integer itemNo : sampledItemNos) {
            SampleItemOutcome outcome = outcomes.getOrDefault(itemNo, SampleItemOutcome.OPEN);
            if (outcome == SampleItemOutcome.OPEN) {
                complete = false;
            } else if (outcome == SampleItemOutcome.DEFECT) {
                defects++;
            }
        }
        return new SampleTally(sampledItemNos.size(), defects, complete);
    }
}
