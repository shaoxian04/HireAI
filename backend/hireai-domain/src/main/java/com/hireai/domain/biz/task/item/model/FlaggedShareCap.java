package com.hireai.domain.biz.task.item.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * How many of a Task's flagged Items (a check sent them to review, D7) are held out of the population the
 * random sample is drawn from (spec S8): {@code share} of the Items, rounded down, but at least {@code minimum}.
 * Flagged Items beyond the cap can be drawn like any other, so an Agent cannot shrink the sample, or move
 * Items it is unsure of out of it, by flagging them (owner decision on #87). The cap decides sample
 * membership only: a flagged Item is never paid with the lot, whatever the cap.
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
