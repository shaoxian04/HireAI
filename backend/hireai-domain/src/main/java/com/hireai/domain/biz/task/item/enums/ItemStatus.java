package com.hireai.domain.biz.task.item.enums;

/**
 * An Item's own state inside a multi-Item Task (spec S3). The Task status aggregates these.
 * <ul>
 *   <li>{@code AWAITING_RESULT}: dispatched with the Task, no result yet.</li>
 *   <li>{@code PENDING_REVIEW}: its result passed the Contract's checks and awaits the Client.</li>
 *   <li>{@code ACCEPTED}: accepted (by the Client, the review window, or the API channel) and paid.</li>
 *   <li>{@code SPEC_VIOLATION}: its result failed the Contract's checks; refunded.</li>
 *   <li>{@code FAILED}: the Agent reported it could not do this Item, or left it out; refunded.</li>
 *   <li>{@code REJECTED}: the buyer's reviewer found it wrong (spec S8, D17'); on its rejection response
 *       path, its escrow still held.</li>
 *   <li>{@code CONCEDED}: the Agent conceded the reviewer's rejection; refunded.</li>
 *   <li>{@code PRO_RATA}: settled by extrapolation after a failed resample (D19); paid in part.</li>
 *   <li>{@code UNREVIEWED}: a check sent it to the buyer's review (D7) and nobody judged it before the review
 *       window closed; refunded, and it does not count towards the Agent's reputation (owner decision on #87).</li>
 * </ul>
 */
public enum ItemStatus {
    AWAITING_RESULT,
    PENDING_REVIEW,
    ACCEPTED,
    SPEC_VIOLATION,
    FAILED,
    REJECTED,
    CONCEDED,
    PRO_RATA,
    UNREVIEWED;

    /** Whether this Item's escrow share has left escrow (paid, refunded, or both in part). */
    public boolean isSettled() {
        return this == ACCEPTED || this == SPEC_VIOLATION || this == FAILED || this == CONCEDED || this == PRO_RATA
                || this == UNREVIEWED;
    }

    /** Settled with nothing paid to the builder. */
    public boolean isRefunded() {
        return this == SPEC_VIOLATION || this == FAILED || this == CONCEDED || this == UNREVIEWED;
    }
}
