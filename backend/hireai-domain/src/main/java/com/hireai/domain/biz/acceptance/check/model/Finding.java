package com.hireai.domain.biz.acceptance.check.model;

import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

/**
 * A fact about the Task's input noticed while accepting it, found by a Check Library entry that
 * needs other Tasks or outside data (spec D7): a duplicate brief, say. It is reported to the Client
 * and never changes what the Agent is paid, so it is kept apart from the checks and never counts
 * towards a verdict.
 */
public record Finding(String code, String detail) {
    public Finding {
        if (code == null || code.isBlank()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "A Finding needs a code");
        }
    }
}
