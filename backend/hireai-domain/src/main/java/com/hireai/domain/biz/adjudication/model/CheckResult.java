package com.hireai.domain.biz.adjudication.model;

import com.hireai.domain.biz.adjudication.enums.CheckOutcome;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

/**
 * One deterministic check run by the validation gate against a result. {@code passed} is true only
 * for {@link CheckOutcome#PASS}. A report stored before outcomes existed has none, and reads back as
 * PASS or REJECT from {@code passed}.
 */
public record CheckResult(String rule, boolean passed, String detail, CheckOutcome outcome) {
    public CheckResult {
        if (rule == null || rule.isBlank()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Check rule is required");
        }
        if (outcome == null) {
            outcome = passed ? CheckOutcome.PASS : CheckOutcome.REJECT;
        }
        if (passed != (outcome == CheckOutcome.PASS)) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "Check " + rule + " cannot be passed=" + passed + " with outcome " + outcome);
        }
    }

    /** A check that passes or rejects. */
    public CheckResult(String rule, boolean passed, String detail) {
        this(rule, passed, detail, null);
    }

    /** A check that could not decide, so the result goes to the Client's review. */
    public static CheckResult review(String rule, String detail) {
        return new CheckResult(rule, false, detail, CheckOutcome.REVIEW);
    }

    public boolean rejects() {
        return outcome == CheckOutcome.REJECT;
    }

    public boolean sendsToReview() {
        return outcome == CheckOutcome.REVIEW;
    }
}
