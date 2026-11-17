package com.hireai.domain.biz.acceptance.aireview.enums;

/**
 * Where a Task's AI Review stands. FROZEN at submit (the fee is in escrow), REQUESTED once a result
 * passed the deterministic checks, then COMPLETED (fee charged), FAILED (the reviewer could not
 * answer; fee refunded) or RELEASED (no result was ever reviewed; fee refunded).
 */
public enum AiReviewStatus {
    FROZEN,
    REQUESTED,
    COMPLETED,
    FAILED,
    RELEASED
}
