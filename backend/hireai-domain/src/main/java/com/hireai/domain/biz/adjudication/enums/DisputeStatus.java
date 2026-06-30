// DisputeStatus.java
package com.hireai.domain.biz.adjudication.enums;

/** Dispute lifecycle. ESCALATED is reserved for tier-2 (a future spec) and unused in tier-1. */
public enum DisputeStatus {
    OPEN,
    ARBITRATING,
    RULED,
    RESOLVED,
    ESCALATED
}
