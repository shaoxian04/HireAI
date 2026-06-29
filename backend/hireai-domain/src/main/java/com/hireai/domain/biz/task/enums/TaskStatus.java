package com.hireai.domain.biz.task.enums;

import java.util.EnumSet;
import java.util.Set;

/**
 * Full task lifecycle, per the SAD (see docs/details/data-model.md).
 * All routing and settlement states are now live. The implemented happy path is
 * SUBMITTED → QUEUED → EXECUTING → RESULT_RECEIVED → RESOLVED (client accept/reject);
 * off-path outcomes: AWAITING_CAPACITY (no eligible agent matched), TIMED_OUT, FAILED.
 * Deferred to future modules: PENDING_REVIEW and SPEC_VIOLATION (Module 4 validation
 * gate); CANCELLED is reserved. PENDING_REVIEW is included in {@link #PENDING_ESCROW}
 * for forward-compatibility with the planned validation gate.
 */
public enum TaskStatus {
    SUBMITTED,
    QUEUED,
    EXECUTING,
    RESULT_RECEIVED,
    PENDING_REVIEW,
    RESOLVED,
    AWAITING_CAPACITY,
    TIMED_OUT,
    SPEC_VIOLATION,
    FAILED,
    CANCELLED;

    /**
     * In-flight statuses for a routed task where escrow is still held — it would pay out to the
     * builder on accept. The single home for this classification (used by builder earnings).
     */
    private static final Set<TaskStatus> PENDING_ESCROW = EnumSet.of(
            QUEUED, EXECUTING, RESULT_RECEIVED, PENDING_REVIEW, AWAITING_CAPACITY);

    public boolean isPendingEscrow() {
        return PENDING_ESCROW.contains(this);
    }
}
