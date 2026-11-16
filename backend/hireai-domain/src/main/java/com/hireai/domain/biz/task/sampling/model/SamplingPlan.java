package com.hireai.domain.biz.task.sampling.model;

import com.hireai.domain.biz.task.sampling.enums.StageVerdict;

/**
 * The sampling plan for one lot (spec S8, D19, D21): how many Items the first (and, for a double
 * plan, the second) sample holds, and the acceptance and rejection numbers of each stage, as ISO
 * 2859-1 sets them. A single plan has no second sample ({@code secondSampleSize} 0): its first sample
 * accepts or rejects the lot on its own. {@code fullInspection}: the sample is the whole lot.
 *
 * <p>Double sampling: with d1 defects in the first sample the lot is accepted when d1 &le; Ac1 and
 * fails when d1 &ge; Re1; in between the second sample is drawn and the lot is accepted when
 * d1 + d2 &le; Ac2, else it fails.
 */
public record SamplingPlan(char codeLetter, int lotSize, int firstSampleSize, int secondSampleSize,
                           int firstAccept, int firstReject, int secondAccept, int secondReject,
                           boolean fullInspection) {

    public boolean isDouble() {
        return secondSampleSize > 0;
    }

    public StageVerdict judgeFirst(int defects) {
        if (defects <= firstAccept) {
            return StageVerdict.ACCEPT;
        }
        if (!isDouble() || defects >= firstReject) {
            return StageVerdict.REJECT;
        }
        return StageVerdict.SECOND_SAMPLE;
    }

    /** {@code cumulativeDefects}: defects found in both samples together. */
    public StageVerdict judgeSecond(int cumulativeDefects) {
        return cumulativeDefects <= secondAccept ? StageVerdict.ACCEPT : StageVerdict.REJECT;
    }
}
