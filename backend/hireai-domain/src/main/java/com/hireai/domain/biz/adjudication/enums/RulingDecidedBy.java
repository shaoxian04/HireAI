// RulingDecidedBy.java
package com.hireai.domain.biz.adjudication.enums;

/** Who produced the ruling: the LLM arbitrator, or the platform's refund fallback (DLQ 兜底). */
public enum RulingDecidedBy {
    ARBITRATOR,
    FALLBACK
}
