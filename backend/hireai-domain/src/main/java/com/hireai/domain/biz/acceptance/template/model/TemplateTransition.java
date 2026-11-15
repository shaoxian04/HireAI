package com.hireai.domain.biz.acceptance.template.model;

import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.time.Duration;

/**
 * How long the previous Template version stays live after a new one is published (spec D36; default
 * 30 days, {@code hireai.acceptance.template-transition}). Zero means a new version replaces the old
 * one at once.
 */
public record TemplateTransition(Duration period) {

    public TemplateTransition {
        if (period == null || period.isNegative()) {
            throw new DomainException(ResultCode.INTERNAL_ERROR, "The Template transition must not be negative");
        }
    }
}
