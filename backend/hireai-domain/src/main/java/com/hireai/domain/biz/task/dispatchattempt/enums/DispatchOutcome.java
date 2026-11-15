package com.hireai.domain.biz.task.dispatchattempt.enums;

/**
 * How one dispatch attempt (one Agent version the platform tried to hand a task to) ended. Closed
 * set: must stay in sync with the V43 CHECK constraint on {@code dispatch_attempts.outcome}.
 */
public enum DispatchOutcome {
    /** The Agent acknowledged the WorkOrder with a 2xx. */
    ACCEPTED,
    /** Every send in the retry schedule failed (the dispatch dead-lettered). */
    UNREACHABLE,
    /** The Agent declined the WorkOrder as busy (429/503). */
    BUSY,
    /** The Agent or its version could no longer take work at dispatch time. */
    UNAVAILABLE,
    /**
     * The Agent refused this task's Contract (HTTP 422 with {@code {"decline":"CONTRACT"}}, #93). Like
     * {@link #BUSY} it is never retried or penalised, but it counts in the Agent's public decline rate.
     */
    CONTRACT_DECLINED;

    /**
     * {@code true} for the outcomes where the Agent answered the WorkOrder's Contract (took it or
     * refused it): the denominator of the public decline rate. Busy, unreachable and unavailable
     * attempts say nothing about the Contract.
     */
    public boolean answeredTheContract() {
        return this == ACCEPTED || this == CONTRACT_DECLINED;
    }
}
