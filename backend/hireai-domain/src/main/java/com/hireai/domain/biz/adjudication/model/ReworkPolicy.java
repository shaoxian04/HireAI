package com.hireai.domain.biz.adjudication.model;

import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

/**
 * How many times a result rejected by a deterministic check goes back to its Agent (spec D17, D41;
 * 2 by default, {@code hireai.acceptance.max-reworks}). The rejection is not appealable: rework is
 * the Agent's remedy, and after the last one the Task ends SPEC_VIOLATION with a refund.
 */
public record ReworkPolicy(int maxReworks) {

    public ReworkPolicy {
        if (maxReworks < 0) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "maxReworks must not be negative");
        }
    }

    /**
     * True when the result just rejected, the {@code rejectionsSoFar}-th rejection of this Task
     * (counting it), may still go back for rework.
     */
    public boolean allowsRework(int rejectionsSoFar) {
        return rejectionsSoFar <= maxReworks;
    }
}
