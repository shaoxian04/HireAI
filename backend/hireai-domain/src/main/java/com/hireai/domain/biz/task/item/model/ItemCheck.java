package com.hireai.domain.biz.task.item.model;

import com.hireai.domain.biz.adjudication.enums.CheckOutcome;

/**
 * One Acceptance Check run against an Item's result: the rule, whether it passed, why not, and its
 * outcome (spec S5): PASS, REJECT (refunded) or REVIEW (the buyer's reviewer must look at this Item
 * itself). {@code passed} is true only for PASS. A check stored before outcomes existed reads back as
 * PASS or REJECT from {@code passed}.
 */
public record ItemCheck(String rule, boolean passed, String detail, CheckOutcome outcome) {

    public ItemCheck {
        if (outcome == null) {
            outcome = passed ? CheckOutcome.PASS : CheckOutcome.REJECT;
        }
        passed = outcome == CheckOutcome.PASS;
    }

    public ItemCheck(String rule, boolean passed, String detail) {
        this(rule, passed, detail, null);
    }

    public boolean rejects() {
        return outcome == CheckOutcome.REJECT;
    }

    public boolean sendsToReview() {
        return outcome == CheckOutcome.REVIEW;
    }
}
