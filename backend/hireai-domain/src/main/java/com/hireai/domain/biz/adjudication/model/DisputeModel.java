package com.hireai.domain.biz.adjudication.model;

import com.hireai.domain.biz.adjudication.enums.DisputeStatus;
import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Dispute aggregate root. Holds an append-only ruling HISTORY (a future Administrator override is
 * appended above the arbitrator's tier-1 ruling, never replacing it — Invariant #2). The effective
 * ruling is the highest-tier entry. Settlement is computed at apply-time from the incoming category,
 * not from this model (Invariant #3) — this aggregate only records what was decided.
 *
 * State machine: OPEN → ARBITRATING → RULED → RESOLVED,
 * with a RESOLVED-via-fallback path from OPEN/ARBITRATING.
 */
public final class DisputeModel {

    private final UUID id;
    private final UUID taskId;
    private final UUID raisedBy;
    private final RejectReason reasonCategory;
    private final DisputeStatus status;
    private final List<Ruling> rulings;
    private final String correlationId;
    private final Instant createdAt;
    private final Instant resolvedAt;

    private DisputeModel(UUID id, UUID taskId, UUID raisedBy, RejectReason reasonCategory,
                         DisputeStatus status, List<Ruling> rulings, String correlationId,
                         Instant createdAt, Instant resolvedAt) {
        this.id = id;
        this.taskId = taskId;
        this.raisedBy = raisedBy;
        this.reasonCategory = reasonCategory;
        this.status = status;
        this.rulings = List.copyOf(rulings);
        this.correlationId = correlationId;
        this.createdAt = createdAt;
        this.resolvedAt = resolvedAt;
    }

    /** Open a new dispute for a disputable rejection (A/B/C only). */
    public static DisputeModel open(UUID taskId, UUID raisedBy, RejectReason reasonCategory,
                                    String correlationId) {
        if (taskId == null || raisedBy == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "taskId and raisedBy are required");
        }
        if (reasonCategory == null || !reasonCategory.opensDispute()) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Dispute reason must be A/B/C; got " + reasonCategory);
        }
        return new DisputeModel(UUID.randomUUID(), taskId, raisedBy, reasonCategory,
                DisputeStatus.OPEN, List.of(), correlationId, Instant.now(), null);
    }

    /** Rebuild from persisted rows (no validation). */
    public static DisputeModel rehydrate(UUID id, UUID taskId, UUID raisedBy,
                                         RejectReason reasonCategory, DisputeStatus status,
                                         List<Ruling> rulings, String correlationId,
                                         Instant createdAt, Instant resolvedAt) {
        return new DisputeModel(id, taskId, raisedBy, reasonCategory, status, rulings,
                correlationId, createdAt, resolvedAt);
    }

    /** OPEN → ARBITRATING (handed off for async arbitration). */
    public DisputeModel startArbitrating() {
        requireStatus(DisputeStatus.OPEN, "startArbitrating");
        return new DisputeModel(id, taskId, raisedBy, reasonCategory, DisputeStatus.ARBITRATING,
                rulings, correlationId, createdAt, resolvedAt);
    }

    /** OPEN|ARBITRATING → RULED: append a ruling to the history. */
    public DisputeModel recordRuling(Ruling ruling) {
        if (status != DisputeStatus.OPEN && status != DisputeStatus.ARBITRATING) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "recordRuling requires OPEN|ARBITRATING; was " + status);
        }
        if (ruling == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "ruling is required");
        }
        return new DisputeModel(id, taskId, raisedBy, reasonCategory, DisputeStatus.RULED,
                append(ruling), correlationId, createdAt, resolvedAt);
    }

    /** RULED → RESOLVED. */
    public DisputeModel resolve() {
        requireStatus(DisputeStatus.RULED, "resolve");
        return new DisputeModel(id, taskId, raisedBy, reasonCategory, DisputeStatus.RESOLVED,
                rulings, correlationId, createdAt, Instant.now());
    }

    /** OPEN|ARBITRATING → RESOLVED via DLQ fallback: append the fallback ruling. */
    public DisputeModel resolveByFallback(Ruling fallbackRuling) {
        if (status != DisputeStatus.OPEN && status != DisputeStatus.ARBITRATING) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "resolveByFallback requires OPEN|ARBITRATING; was " + status);
        }
        if (fallbackRuling == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "fallback ruling is required");
        }
        return new DisputeModel(id, taskId, raisedBy, reasonCategory, DisputeStatus.RESOLVED,
                append(fallbackRuling), correlationId, createdAt, Instant.now());
    }

    /** True while a ruling can still be applied (first-ruling-wins guard). */
    public boolean isResolvable() {
        return status == DisputeStatus.OPEN || status == DisputeStatus.ARBITRATING;
    }

    /** The highest-tier ruling, or empty if none recorded yet. */
    public Optional<Ruling> effectiveRuling() {
        return rulings.stream().max(Comparator.comparingInt(Ruling::tier));
    }

    private List<Ruling> append(Ruling ruling) {
        List<Ruling> next = new ArrayList<>(rulings);
        next.add(ruling);
        return next;
    }

    private void requireStatus(DisputeStatus expected, String op) {
        if (status != expected) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    op + " requires " + expected + "; was " + status);
        }
    }

    public UUID id() { return id; }
    public UUID taskId() { return taskId; }
    public UUID raisedBy() { return raisedBy; }
    public RejectReason reasonCategory() { return reasonCategory; }
    public DisputeStatus status() { return status; }
    public List<Ruling> rulings() { return rulings; }
    public String correlationId() { return correlationId; }
    public Instant createdAt() { return createdAt; }
    public Instant resolvedAt() { return resolvedAt; }
}
