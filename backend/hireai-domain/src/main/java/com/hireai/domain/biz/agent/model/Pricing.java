package com.hireai.domain.biz.agent.model;

import com.hireai.utility.result.ResultCode;
import com.hireai.utility.exception.DomainException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pricing value object for an Agent version: the per-task price charged to the client,
 * a non-negative amount normalised to 2 decimal places. Immutable. Kept as BigDecimal
 * (not Money) because the routing read-model carries price as a raw BigDecimal.
 */
public record Pricing(BigDecimal price) {

    public static Pricing of(BigDecimal amount) {
        if (amount == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Price is required");
        }
        if (amount.signum() < 0) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Price must be non-negative");
        }
        return new Pricing(amount.setScale(2, RoundingMode.HALF_UP));
    }
}
