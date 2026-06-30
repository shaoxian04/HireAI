package com.hireai.domain.biz.task.enums;

/** How the client resolved a RESULT_RECEIVED task. Null on the task until it is RESOLVED. */
public enum TaskResolution {
    ACCEPTED,
    REJECTED,
    /** Partial-fulfilment dispute ruling: builder paid 85/15 on half the budget, client refunded the other half. */
    PARTIALLY_ACCEPTED
}
