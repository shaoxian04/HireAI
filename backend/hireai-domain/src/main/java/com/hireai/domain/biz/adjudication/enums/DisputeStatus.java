// DisputeStatus.java
package com.hireai.domain.biz.adjudication.enums;

/**
 * Dispute lifecycle. ARBITRATING/RULED hold the tier-1 arbitrator proposal (escrow stays frozen at
 * RULED until the client accepts or appeals); ESCALATED is a client appeal, or a stranded dispute
 * (DLQ / arbitration-timeout sweeper), awaiting the tier-2 Administrator backstop.
 */
public enum DisputeStatus {
    OPEN,
    ARBITRATING,
    RULED,
    RESOLVED,
    ESCALATED
}
