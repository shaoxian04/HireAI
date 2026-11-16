package com.hireai.domain.biz.adjudication.enums;

/**
 * A validation report's overall outcome: {@code FAIL} when any check rejects, else {@code REVIEW}
 * when any check sends the result to review (an undecidable check or a self-reported anomaly),
 * else {@code PASS}. PASS and REVIEW both reach the Client's review; only PASS may auto-settle.
 */
public enum Verdict {
    PASS,
    FAIL,
    REVIEW
}
