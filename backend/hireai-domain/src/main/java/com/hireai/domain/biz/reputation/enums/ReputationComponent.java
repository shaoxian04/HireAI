package com.hireai.domain.biz.reputation.enums;

/**
 * Which of the two independent estimators an event feeds (ADR 0003).
 *
 * <p>The split is the load-bearing decision of the module. Folding both into one stream makes an
 * unreviewed acceptance count as maximum quality, so silence becomes perfection, any rating below
 * 5★ is a penalty, and suppressing reviews becomes the rational builder strategy. Kept apart, an
 * unrated agent falls back to the neutral prior instead: silence reads as <em>unknown</em>, never
 * as <em>perfect</em>.
 */
public enum ReputationComponent {

    /** Platform-witnessed: dense, objective, unforgeable without doing real work. */
    RELIABILITY,

    /** Client-authored: sparse, subjective, opt-in, forgeable — hence the higher prior strength. */
    SATISFACTION
}
