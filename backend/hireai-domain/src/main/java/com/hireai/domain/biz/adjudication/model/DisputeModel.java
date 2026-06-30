// DisputeModel.java
package com.hireai.domain.biz.adjudication.model;

import com.hireai.domain.biz.adjudication.enums.DisputeStatus;
import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.time.Instant;
import java.util.UUID;

/**
 * Dispute aggregate root (one per task). Immutable: each transition returns a new copy; an illegal
 * transition throws {@link DomainException}. State machine: OPEN → ARBITRATING → RULED → RESOLVED,
 * with a RESOLVED-via-fallback path from OPEN/ARBITRATING. ESCALATED is a reserved tier-2 seam.
 */
public final class DisputeModel {

    private final UUID id;
    private final UUID taskId;
    private final UUID raisedBy;
    private final RejectReason reasonCategory;  // always a dispute reason (A/B/C)
    private final DisputeStatus status;
    private final Ruling ruling;                // nullable until RULED/RESOLVED
    private final String correlationId;
    private final Instant createdAt;
    private final Instant resolvedAt;           // nullable until RESOLVED

    private DisputeModel(UUID id, UUID taskId, UUID raisedBy, RejectReason reasonCategory,
                         DisputeStatus status, Ruling ruling, String correlationId,
                         Instant createdAt, Instant resolvedAt) {
        this.id = id;
        this.taskId = taskId;
        this.raisedBy = raisedBy;
        this.reasonCategory = reasonCategory;
        this.status = status;
        this.ruling = ruling;
        this.correlationId = correlationId;
        this.createdAt = createdAt;
        this.resolvedAt = resolvedAt;
    }

    /** Open a new dispute for a disputable rejection (A/B/C only). */
    public static DisputeModel open(UUID taskId, UUID raisedBy, RejectReason reasonCategory, String correlationId) {
        if (taskId == null || raisedBy == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "taskId and raisedBy are required");
        }
        if (reasonCategory == null || !reasonCategory.opensDispute()) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Dispute reason must be A/B/C; got " + reasonCategory);
        }
        return new DisputeModel(UUID.randomUUID(), taskId, raisedBy, reasonCategory,
                DisputeStatus.OPEN, null, correlationId, Instant.now(), null);
    }

    /** Rebuild from a persisted row (no validation). */
    public static DisputeModel rehydrate(UUID id, UUID taskId, UUID raisedBy, RejectReason reasonCategory,
                                         DisputeStatus status, Ruling ruling, String correlationId,
                                         Instant createdAt, Instant resolvedAt) {
        return new DisputeModel(id, taskId, raisedBy, reasonCategory, status, ruling,
                correlationId, createdAt, resolvedAt);
    }

    /** OPEN → ARBITRATING: the request was handed to the arbitrator (async transport). */
    public DisputeModel startArbitrating() {
        requireStatusIn("startArbitrating", DisputeStatus.OPEN);
        return copyWith(DisputeStatus.ARBITRATING, this.ruling, this.resolvedAt);
    }

    /** OPEN|ARBITRATING → RULED: a ruling arrived (first-ruling-wins; later rulings are rejected by the guard). */
    public DisputeModel recordRuling(Ruling ruling) {
        requireStatusIn("recordRuling", DisputeStatus.OPEN, DisputeStatus.ARBITRATING);
        if (ruling == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "ruling is required");
        }
        return copyWith(DisputeStatus.RULED, ruling, this.resolvedAt);
    }

    /** RULED → RESOLVED: the ruling has been settled. */
    public DisputeModel resolve() {
        requireStatusIn("resolve", DisputeStatus.RULED);
        return copyWith(DisputeStatus.RESOLVED, this.ruling, Instant.now());
    }

    /** OPEN|ARBITRATING → RESOLVED via the platform refund fallback (DLQ 兜底). */
    public DisputeModel resolveByFallback(Ruling fallbackRuling) {
        requireStatusIn("resolveByFallback", DisputeStatus.OPEN, DisputeStatus.ARBITRATING);
        if (fallbackRuling == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "fallback ruling is required");
        }
        return copyWith(DisputeStatus.RESOLVED, fallbackRuling, Instant.now());
    }

    /** True while a ruling can still be applied (used for first-ruling-wins idempotency). */
    public boolean isResolvable() {
        return status == DisputeStatus.OPEN || status == DisputeStatus.ARBITRATING;
    }

    private DisputeModel copyWith(DisputeStatus newStatus, Ruling newRuling, Instant newResolvedAt) {
        return new DisputeModel(id, taskId, raisedBy, reasonCategory, newStatus, newRuling,
                correlationId, createdAt, newResolvedAt);
    }

    private void requireStatusIn(String transition, DisputeStatus... allowed) {
        for (DisputeStatus s : allowed) {
            if (this.status == s) {
                return;
            }
        }
        throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                "Illegal dispute transition " + transition + " from " + this.status);
    }

    public UUID id() { return id; }
    public UUID taskId() { return taskId; }
    public UUID raisedBy() { return raisedBy; }
    public RejectReason reasonCategory() { return reasonCategory; }
    public DisputeStatus status() { return status; }
    public Ruling ruling() { return ruling; }
    public String correlationId() { return correlationId; }
    public Instant createdAt() { return createdAt; }
    public Instant resolvedAt() { return resolvedAt; }
}
