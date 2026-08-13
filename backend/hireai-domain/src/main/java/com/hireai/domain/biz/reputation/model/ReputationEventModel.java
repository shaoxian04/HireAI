package com.hireai.domain.biz.reputation.model;

import com.hireai.domain.biz.reputation.enums.ReputationComponent;
import com.hireai.domain.biz.reputation.enums.ReputationEventType;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * One immutable sample in an agent's append-only reputation history (Invariant #2).
 *
 * <p>Carries a {@code quality} in [0,1] saying what the sample asserts and a {@code weight} saying
 * how much it counts. There is no mutating behaviour and no factory for editing one: corrections
 * are compensating entries, exactly as in the ledger, and the database refuses UPDATE and DELETE.
 *
 * <p>{@code taskId} is a soft reference — no cross-context FK — following the precedent set by
 * validation_reports and api_key_task, so the Task aggregate is untouched.
 */
public final class ReputationEventModel {

    private static final int QUALITY_SCALE = 3;
    private static final BigDecimal MAX_STARS = BigDecimal.valueOf(5);

    private final UUID id;
    private final UUID agentId;
    private final UUID taskId;
    private final ReputationEventType eventType;
    private final BigDecimal quality;
    private final BigDecimal weight;
    private final Instant occurredAt;

    private ReputationEventModel(UUID id, UUID agentId, UUID taskId, ReputationEventType eventType,
                                 BigDecimal quality, BigDecimal weight, Instant occurredAt) {
        this.id = id;
        this.agentId = agentId;
        this.taskId = taskId;
        this.eventType = eventType;
        this.quality = quality;
        this.weight = weight;
        this.occurredAt = occurredAt;
    }

    /**
     * A platform-witnessed outcome. Quality comes from the event type itself, so no caller can
     * assert something the type does not mean.
     */
    public static ReputationEventModel outcome(UUID agentId, UUID taskId, ReputationEventType type) {
        if (type == null || type.component() != ReputationComponent.RELIABILITY) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "Not a platform-witnessed outcome type: " + type);
        }
        return create(agentId, taskId, type, type.fixedQuality(), BigDecimal.ONE);
    }

    /**
     * A client rating, mapped linearly onto the unit interval: (stars − 1) / 4, so 1★ asserts 0.0
     * and 5★ asserts 1.0.
     */
    public static ReputationEventModel rating(UUID agentId, UUID taskId, int stars) {
        if (stars < 1 || stars > MAX_STARS.intValue()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "Rating must be between 1 and 5; got " + stars);
        }
        BigDecimal quality = BigDecimal.valueOf(stars - 1L)
                .divide(BigDecimal.valueOf(4), QUALITY_SCALE, RoundingMode.HALF_UP);
        return create(agentId, taskId, ReputationEventType.RATING, quality, BigDecimal.ONE);
    }

    /** Rehydration from persistence. */
    public static ReputationEventModel rehydrate(UUID id, UUID agentId, UUID taskId,
                                                 ReputationEventType eventType, BigDecimal quality,
                                                 BigDecimal weight, Instant occurredAt) {
        return new ReputationEventModel(id, agentId, taskId, eventType, quality, weight, occurredAt);
    }

    private static ReputationEventModel create(UUID agentId, UUID taskId, ReputationEventType type,
                                               BigDecimal quality, BigDecimal weight) {
        if (agentId == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "agentId is required");
        }
        if (quality == null || quality.signum() < 0 || quality.compareTo(BigDecimal.ONE) > 0) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "Reputation quality must be in [0,1]; got " + quality);
        }
        if (weight == null || weight.signum() <= 0) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "Reputation weight must be > 0; got " + weight);
        }
        return new ReputationEventModel(UUID.randomUUID(), agentId, taskId, type,
                quality.setScale(QUALITY_SCALE, RoundingMode.HALF_UP),
                weight.setScale(QUALITY_SCALE, RoundingMode.HALF_UP),
                Instant.now());
    }

    /** The contribution this event makes to its component's summed quality. */
    public BigDecimal weightedQuality() {
        return quality.multiply(weight);
    }

    public ReputationComponent component() { return eventType.component(); }

    public UUID id() { return id; }
    public UUID agentId() { return agentId; }
    public UUID taskId() { return taskId; }
    public ReputationEventType eventType() { return eventType; }
    public BigDecimal quality() { return quality; }
    public BigDecimal weight() { return weight; }
    public Instant occurredAt() { return occurredAt; }
}
