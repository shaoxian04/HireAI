package com.hireai.domain.biz.task.enums;

/**
 * Full task lifecycle, per the SAD (see docs/details/data-model.md). Only
 * {@link #SUBMITTED} is reachable in the current slice; the remaining states are
 * declared for schema forward-compatibility and land with the routing, validation,
 * dispute, and settlement modules. The happy path is
 * SUBMITTED → QUEUED → EXECUTING → RESULT_RECEIVED → PENDING_REVIEW → RESOLVED;
 * the rest are off-path terminal/holding states.
 */
public enum TaskStatus {
    SUBMITTED,
    QUEUED,
    EXECUTING,
    RESULT_RECEIVED,
    PENDING_REVIEW,
    RESOLVED,
    AWAITING_CAPACITY,
    TIMED_OUT,
    SPEC_VIOLATION,
    FAILED,
    CANCELLED
}
