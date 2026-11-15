package com.hireai.domain.biz.task.rejection.enums;

/** What an Agent did with a Client's A/B/C rejection (#95, spec D20). */
public enum AgentResponse {
    /** Delivered a revised result, which is validated and reviewed again. Allowed once per task. */
    REVISE,
    /** Accepted the rejection: the Client is refunded and the Agent records quality 0. */
    CONCEDE,
    /** Stood by its result, with a reason or (by staying silent) without one; the Client decides. */
    STAND_BY
}
