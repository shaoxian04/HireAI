package com.hireai.domain.biz.task.enums;

/**
 * Full task lifecycle. Only {@link #SUBMITTED} is reachable in the current slice;
 * the remaining states are declared for schema forward-compatibility and land with
 * the routing, validation, dispute, and settlement modules.
 */
public enum TaskStatus {
    SUBMITTED,
    ROUTING,
    IN_PROGRESS,
    SUBMITTED_FOR_REVIEW,
    VALIDATING,
    ACCEPTED,
    REJECTED,
    DISPUTED,
    SETTLED,
    CANCELLED
}
