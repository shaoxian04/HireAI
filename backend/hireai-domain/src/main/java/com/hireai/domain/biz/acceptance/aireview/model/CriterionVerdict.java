package com.hireai.domain.biz.acceptance.aireview.model;

import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

/** The reviewer's pass or fail on one AI Review criterion, with its reason (spec D37). */
public record CriterionVerdict(String criterion, boolean passed, String reason) {

    /** Longest reason kept; a longer one is cut. */
    public static final int MAX_REASON_LENGTH = 1000;

    public CriterionVerdict {
        if (criterion == null || criterion.isBlank()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "A verdict must name its criterion");
        }
        reason = reason == null || reason.isBlank() ? (passed ? "Met." : "Not met.") : reason.trim();
        if (reason.length() > MAX_REASON_LENGTH) {
            reason = reason.substring(0, MAX_REASON_LENGTH - 1) + "…";
        }
    }
}
