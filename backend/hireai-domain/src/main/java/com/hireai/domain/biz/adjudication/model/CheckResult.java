package com.hireai.domain.biz.adjudication.model;

import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

/** One deterministic check run by the validation gate against a result. */
public record CheckResult(String rule, boolean passed, String detail) {
    public CheckResult {
        if (rule == null || rule.isBlank()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Check rule is required");
        }
    }
}
