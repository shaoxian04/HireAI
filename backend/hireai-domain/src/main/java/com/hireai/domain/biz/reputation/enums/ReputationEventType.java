package com.hireai.domain.biz.reputation.enums;

import java.math.BigDecimal;

/**
 * The terminal outcomes that move an agent's reputation, and what each one asserts (design §6).
 *
 * <p>Each constant carries the quality its sample asserts and which component it feeds. Note what
 * is <strong>absent</strong>: a changed-mind rejection and a capacity cancellation have no constant
 * here at all, because they must emit nothing. Emission is explicit at each transition site and is
 * never derived from how money moved or from {@code task.resolution} — {@code chargeChangedMind}
 * writes {@code REJECTED} while paying the builder the full 85/15, so a rule keyed off either
 * signal gets that case backwards in both directions.
 */
public enum ReputationEventType {

    /** Client accepted, or a programmatic task passed validation and auto-settled. */
    TASK_ACCEPTED(ReputationComponent.RELIABILITY, BigDecimal.ONE),

    /** Output failed validation against the binding output_spec (Invariant #4). */
    SPEC_VIOLATION(ReputationComponent.RELIABILITY, BigDecimal.ZERO),

    /** The agent reported a non-COMPLETED result on its callback. */
    EXECUTION_FAILED(ReputationComponent.RELIABILITY, BigDecimal.ZERO),

    /** The execution deadline passed with no result. */
    EXECUTION_TIMEOUT(ReputationComponent.RELIABILITY, BigDecimal.ZERO),

    /** Arbitration ruled the work FULFILLED — being complained about is not itself a failure. */
    DISPUTE_WON(ReputationComponent.RELIABILITY, BigDecimal.ONE),

    /** PARTIALLY_FULFILLED — a proportionate consequence, distinct from total failure. */
    DISPUTE_PARTIAL(ReputationComponent.RELIABILITY, new BigDecimal("0.5")),

    /** NOT_FULFILLED. */
    DISPUTE_LOST(ReputationComponent.RELIABILITY, BigDecimal.ZERO),

    /**
     * A client rating. Quality is carried per-event rather than by the constant, because it is
     * derived from the stars: (stars − 1) / 4.
     */
    RATING(ReputationComponent.SATISFACTION, null);

    private final ReputationComponent component;
    private final BigDecimal fixedQuality;

    ReputationEventType(ReputationComponent component, BigDecimal fixedQuality) {
        this.component = component;
        this.fixedQuality = fixedQuality;
    }

    public ReputationComponent component() {
        return component;
    }

    /** The quality this outcome asserts, or null for {@link #RATING} which carries its own. */
    public BigDecimal fixedQuality() {
        return fixedQuality;
    }
}
