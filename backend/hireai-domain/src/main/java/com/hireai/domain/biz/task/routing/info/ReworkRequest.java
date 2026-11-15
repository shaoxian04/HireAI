package com.hireai.domain.biz.task.routing.info;

import java.util.List;

/**
 * Carried on a rework WorkOrder (spec D17): the result a deterministic check rejected goes back to
 * the same Agent with the checks it failed. {@code reworkNo} counts from 1 up to {@code maxReworks};
 * a result failing after the last one ends the Task SPEC_VIOLATION with a refund.
 */
public record ReworkRequest(int reworkNo, int maxReworks, List<FailedCheck> failedChecks) {

    /** One check the rejected result failed, as the Agent is told it. */
    public record FailedCheck(String rule, String detail) {
    }

    public ReworkRequest {
        failedChecks = failedChecks == null ? List.of() : List.copyOf(failedChecks);
    }
}
