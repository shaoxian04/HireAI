package com.hireai.domain.biz.task.sampling.enums;

import com.hireai.domain.biz.task.item.enums.ItemStatus;

/**
 * How one Item stands for the error rate (D19, D17′): {@code PASS} once accepted, {@code DEFECT} once
 * refunded for being wrong (conceded, or a revision that failed its checks), {@code OPEN} while it
 * awaits the reviewer or is on its rejection response path.
 */
public enum SampleItemOutcome {
    PASS,
    DEFECT,
    OPEN;

    /** How an Item in {@code status} counts: accepted passes, refunded is a defect, anything else is open. */
    public static SampleItemOutcome of(ItemStatus status) {
        if (status == ItemStatus.ACCEPTED) {
            return PASS;
        }
        return status.isRefunded() ? DEFECT : OPEN;
    }
}
