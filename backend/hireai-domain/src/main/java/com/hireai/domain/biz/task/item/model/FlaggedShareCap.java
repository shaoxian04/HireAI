package com.hireai.domain.biz.task.item.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * How many of a Task's Items a check may send to the reviewer's own judgement (D7) and so hold out of the
 * sampled lot (spec S8): {@code share} of the Items, rounded down, but at least {@code minimum}. Flagged
 * Items beyond the cap stay in the random lot, so an Agent cannot shrink the sample, or move Items it is
 * unsure of out of it, by flagging them (owner decision on #87).
 */
public record FlaggedShareCap(BigDecimal share, int minimum) {

    public FlaggedShareCap {
        if (share == null || share.signum() < 0 || share.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("flagged share must be between 0 and 1, got " + share);
        }
        if (minimum < 0) {
            throw new IllegalArgumentException("flagged minimum must not be negative, got " + minimum);
        }
    }

    /** The most flagged Items a Task of {@code itemCount} Items holds out of the lot. */
    public int allowed(int itemCount) {
        int byShare = share.multiply(BigDecimal.valueOf(itemCount)).setScale(0, RoundingMode.DOWN).intValueExact();
        return Math.min(itemCount, Math.max(minimum, byShare));
    }
}
