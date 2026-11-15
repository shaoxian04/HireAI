package com.hireai.domain.biz.adjudication.enums;

/**
 * What one Check Library entry concludes about a result (spec S5): it passes, it rejects the result
 * deterministically (reworked, then refunded; not appealable, D17), or it cannot decide and sends
 * the result to the Client's review.
 */
public enum CheckOutcome {
    PASS,
    REJECT,
    REVIEW
}
