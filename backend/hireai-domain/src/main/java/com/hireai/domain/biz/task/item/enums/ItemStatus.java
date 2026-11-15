package com.hireai.domain.biz.task.item.enums;

/**
 * An Item's own state inside a multi-Item Task (spec S3). The Task status aggregates these.
 * <ul>
 *   <li>{@code AWAITING_RESULT}: dispatched with the Task, no result yet.</li>
 *   <li>{@code PENDING_REVIEW}: its result passed the Contract's checks and awaits the Client.</li>
 *   <li>{@code ACCEPTED}: accepted (by the Client, the review window, or the API channel) and paid.</li>
 *   <li>{@code SPEC_VIOLATION}: its result failed the Contract's checks; refunded.</li>
 *   <li>{@code FAILED}: the Agent reported it could not do this Item, or left it out; refunded.</li>
 * </ul>
 */
public enum ItemStatus {
    AWAITING_RESULT,
    PENDING_REVIEW,
    ACCEPTED,
    SPEC_VIOLATION,
    FAILED;

    /** Whether this Item's escrow share has left escrow (paid or refunded). */
    public boolean isSettled() {
        return this == ACCEPTED || this == SPEC_VIOLATION || this == FAILED;
    }
}
