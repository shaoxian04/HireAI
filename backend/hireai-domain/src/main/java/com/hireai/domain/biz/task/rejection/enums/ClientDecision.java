package com.hireai.domain.biz.task.rejection.enums;

/** What the Client decided after the Agent stood by a rejected result (#95, spec D20). */
public enum ClientDecision {
    /** Accepted the result after all: settled as an acceptance. Silence counts as this. */
    ACCEPT,
    /** Asked for AI arbitration; the frozen escrow is the security, so no deposit is taken. */
    ARBITRATE
}
