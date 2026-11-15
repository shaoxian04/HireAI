package com.hireai.domain.biz.task.enums;

import java.util.EnumSet;
import java.util.Set;

/**
 * Full task lifecycle, per the SAD (see docs/details/data-model.md).
 * The implemented happy path is SUBMITTED → QUEUED → EXECUTING → RESULT_RECEIVED →
 * PENDING_REVIEW → RESOLVED (client accept/reject). A rejection for reason A/B/C takes the
 * rejection response path (#95): PENDING_REVIEW → AWAITING_AGENT_RESPONSE, then a revision
 * (→ RESULT_RECEIVED → validation again), a concession (→ RESOLVED, refund) or a stand-by
 * (→ AWAITING_CLIENT_DECISION → RESOLVED on acceptance, or → DISPUTED → RESOLVED on arbitration). Off-path outcomes: AWAITING_AGENT
 * (no eligible agent matched), TIMED_OUT, FAILED, SPEC_VIOLATION.
 * PENDING_REVIEW (validation passed) and SPEC_VIOLATION (validation failed → auto-refund)
 * are produced by the Module 4 validation gate in the agent callback.
 * CANCELLED is produced by the re-match sweeper when its re-match attempts are exhausted
 * (AWAITING_AGENT → CANCELLED, with a full escrow refund). PENDING_REVIEW, the two rejection
 * response states and DISPUTED are included in {@link #PENDING_ESCROW}.
 */
public enum TaskStatus {
    SUBMITTED,
    QUEUED,
    EXECUTING,
    RESULT_RECEIVED,
    PENDING_REVIEW,
    /** The Client rejected for reason A/B/C; the Agent may revise once, concede or stand by (#95, D20). */
    AWAITING_AGENT_RESPONSE,
    /** The Agent stood by its result (or stayed silent); the Client accepts or requests arbitration. */
    AWAITING_CLIENT_DECISION,
    DISPUTED,
    RESOLVED,
    AWAITING_AGENT,
    TIMED_OUT,
    SPEC_VIOLATION,
    FAILED,
    CANCELLED;

    /**
     * In-flight statuses for a routed task where escrow is still held — it would pay out to the
     * builder on accept. The single home for this classification (used by builder earnings).
     */
    private static final Set<TaskStatus> PENDING_ESCROW = EnumSet.of(
            QUEUED, EXECUTING, RESULT_RECEIVED, PENDING_REVIEW, AWAITING_AGENT_RESPONSE,
            AWAITING_CLIENT_DECISION, DISPUTED, AWAITING_AGENT);

    public boolean isPendingEscrow() {
        return PENDING_ESCROW.contains(this);
    }
}
