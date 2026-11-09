package com.hireai.domain.biz.task.dispatchattempt.enums;

/**
 * How one dispatch attempt (one Agent version the platform tried to hand a task to) ended. Closed
 * set: must stay in sync with the V33 CHECK constraint on {@code dispatch_attempts.outcome}.
 */
public enum DispatchOutcome {
    /** The Agent acknowledged the WorkOrder with a 2xx. */
    ACCEPTED,
    /** Every send in the retry schedule failed (the dispatch dead-lettered). */
    UNREACHABLE,
    /** The Agent declined the WorkOrder as busy (429/503). */
    BUSY,
    /** The Agent or its version could no longer take work at dispatch time. */
    UNAVAILABLE
}
