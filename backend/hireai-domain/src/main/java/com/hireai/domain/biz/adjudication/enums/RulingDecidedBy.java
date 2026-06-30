// RulingDecidedBy.java
package com.hireai.domain.biz.adjudication.enums;

/** Who produced the ruling: the LLM arbitrator, a tier-2 admin override, or the platform's refund fallback (DLQ 兜底). */
public enum RulingDecidedBy {
    ARBITRATOR,
    ADMINISTRATOR,  // tier-2 override; reserved seam — no writer yet (Module 4 admin tier deferred)
    FALLBACK
}
