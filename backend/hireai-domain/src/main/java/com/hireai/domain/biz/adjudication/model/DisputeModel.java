package com.hireai.domain.biz.adjudication.model;

import com.hireai.domain.biz.adjudication.enums.DisputeParty;
import com.hireai.domain.biz.adjudication.enums.DisputeStatus;
import com.hireai.domain.biz.adjudication.enums.RulingDecidedBy;
import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Dispute aggregate root. Holds an append-only ruling HISTORY (a future Administrator override is
 * appended above the arbitrator's tier-1 ruling, never replacing it — Invariant #2). The effective
 * ruling is the highest-tier entry. Settlement is computed at apply-time from the incoming category,
 * not from this model (Invariant #3) — this aggregate only records what was decided.
 *
 * State machine: OPEN → ARBITRATING → RULED → RESOLVED,
 * with a RESOLVED-via-fallback path from OPEN/ARBITRATING, and from RULED branching either to
 * RESOLVED (once every side the ruling goes against accepts it, or the window lapses) or ESCALATED
 * (one appeal, by such a side).
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
    private final Acceptance acceptance;

    /**
     * Who has accepted the proposed ruling and who appealed it (D42). Only a side the ruling goes
     * against may do either; a ruling is appealed at most once.
     */
    public record Acceptance(Instant clientAcceptedAt, Instant builderAcceptedAt,
                             DisputeParty appealedBy, Instant appealedAt) {
        public static final Acceptance NONE = new Acceptance(null, null, null, null);

        Instant acceptedAt(DisputeParty party) {
            return party == DisputeParty.CLIENT ? clientAcceptedAt : builderAcceptedAt;
        }

        Acceptance withAccepted(DisputeParty party, Instant at) {
            return party == DisputeParty.CLIENT
                    ? new Acceptance(at, builderAcceptedAt, appealedBy, appealedAt)
                    : new Acceptance(clientAcceptedAt, at, appealedBy, appealedAt);
        }
    }

    private DisputeModel(UUID id, UUID taskId, UUID raisedBy, RejectReason reasonCategory,
                         DisputeStatus status, List<Ruling> rulings, String correlationId,
                         Instant createdAt, Instant resolvedAt, Acceptance acceptance) {
        this.id = id;
        this.taskId = taskId;
        this.raisedBy = raisedBy;
        this.reasonCategory = reasonCategory;
        this.status = status;
        this.rulings = List.copyOf(rulings);
        this.correlationId = correlationId;
        this.createdAt = createdAt;
        this.resolvedAt = resolvedAt;
        this.acceptance = acceptance == null ? Acceptance.NONE : acceptance;
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
                DisputeStatus.OPEN, List.of(), correlationId, Instant.now(), null, Acceptance.NONE);
    }

    /** Rebuild from persisted rows (no validation). */
    public static DisputeModel rehydrate(UUID id, UUID taskId, UUID raisedBy,
                                         RejectReason reasonCategory, DisputeStatus status,
                                         List<Ruling> rulings, String correlationId,
                                         Instant createdAt, Instant resolvedAt) {
        return rehydrate(id, taskId, raisedBy, reasonCategory, status, rulings, correlationId, createdAt,
                resolvedAt, Acceptance.NONE);
    }

    /** Rebuild from persisted rows, with who accepted or appealed the proposed ruling. */
    public static DisputeModel rehydrate(UUID id, UUID taskId, UUID raisedBy,
                                         RejectReason reasonCategory, DisputeStatus status,
                                         List<Ruling> rulings, String correlationId,
                                         Instant createdAt, Instant resolvedAt, Acceptance acceptance) {
        return new DisputeModel(id, taskId, raisedBy, reasonCategory, status, rulings,
                correlationId, createdAt, resolvedAt, acceptance);
    }

    /** OPEN → ARBITRATING (handed off for async arbitration). */
    public DisputeModel startArbitrating() {
        requireStatus(DisputeStatus.OPEN, "startArbitrating");
        return new DisputeModel(id, taskId, raisedBy, reasonCategory, DisputeStatus.ARBITRATING,
                rulings, correlationId, createdAt, resolvedAt, acceptance);
    }

    /**
     * OPEN|ARBITRATING → ESCALATED: hand a stranded dispute to the human admin backstop. Written by
     * the DLQ listener (loud failure) and the stale-arbitration sweeper (quiet failure). Records no
     * ruling — escalation only flags "needs admin". isResolvable() now returns false, so a late
     * ARBITRATOR callback is ignored (the admin owns it).
     */
    public DisputeModel escalate() {
        if (status != DisputeStatus.OPEN && status != DisputeStatus.ARBITRATING) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "escalate requires OPEN|ARBITRATING; was " + status);
        }
        return new DisputeModel(id, taskId, raisedBy, reasonCategory, DisputeStatus.ESCALATED,
                rulings, correlationId, createdAt, resolvedAt, acceptance);
    }

    /**
     * RULED → ESCALATED: a side the proposed ruling goes against appeals it to the human admin, once
     * (D42). The arbitrator ruling stays in the history as the admin's context; settlement has not
     * run yet (delayed-settlement model), so no money reversal is involved.
     */
    public DisputeModel appeal(DisputeParty party, Instant now) {
        requireStatus(DisputeStatus.RULED, "appeal");
        requireMayAct(party);
        return new DisputeModel(id, taskId, raisedBy, reasonCategory, DisputeStatus.ESCALATED,
                rulings, correlationId, createdAt, resolvedAt,
                new Acceptance(acceptance.clientAcceptedAt(), acceptance.builderAcceptedAt(), party, now));
    }

    /**
     * A side the proposed ruling goes against accepts it. The ruling settles once every such side
     * has accepted ({@link #acceptedByEverySideThatMayAppeal()}), or when the appeal window lapses.
     */
    public DisputeModel accept(DisputeParty party, Instant now) {
        requireStatus(DisputeStatus.RULED, "accept");
        requireMayAct(party);
        return new DisputeModel(id, taskId, raisedBy, reasonCategory, status, rulings, correlationId,
                createdAt, resolvedAt, acceptance.withAccepted(party, now));
    }

    /**
     * The sides that may still accept or appeal the proposed ruling: those it goes against, while it
     * is the arbitrator's proposal, nobody has appealed it, and they have not accepted it yet.
     */
    public Set<DisputeParty> partiesWhoMayAppeal() {
        if (status != DisputeStatus.RULED || appealedOrAdminRuled()) {
            return Set.of();
        }
        Set<DisputeParty> parties = EnumSet.noneOf(DisputeParty.class);
        effectiveRuling().ifPresent(r -> r.category().partiesItGoesAgainst().stream()
                .filter(p -> acceptance.acceptedAt(p) == null)
                .forEach(parties::add));
        return parties;
    }

    /** True once every side the proposed ruling goes against has accepted it. */
    public boolean acceptedByEverySideThatMayAppeal() {
        return effectiveRuling()
                .map(r -> r.category().partiesItGoesAgainst().stream()
                        .allMatch(p -> acceptance.acceptedAt(p) != null))
                .orElse(false);
    }

    private boolean appealedOrAdminRuled() {
        return acceptance.appealedBy() != null || effectiveRuling()
                .map(r -> r.decidedBy() == RulingDecidedBy.ADMINISTRATOR).orElse(false);
    }

    private void requireMayAct(DisputeParty party) {
        if (appealedOrAdminRuled()) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION, "A ruling can be appealed only once");
        }
        Ruling ruling = effectiveRuling().orElseThrow(() -> new DomainException(
                ResultCode.DOMAIN_RULE_VIOLATION, "Dispute " + id + " has no proposed ruling"));
        if (!ruling.category().partiesItGoesAgainst().contains(party)) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "The ruling is in your favour; there is nothing to accept or appeal");
        }
        if (acceptance.acceptedAt(party) != null) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION, "You already accepted this ruling");
        }
    }

    /** OPEN|ARBITRATING|ESCALATED → RULED: append a ruling to the history. */
    public DisputeModel recordRuling(Ruling ruling) {
        if (status != DisputeStatus.OPEN && status != DisputeStatus.ARBITRATING
                && status != DisputeStatus.ESCALATED) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "recordRuling requires OPEN|ARBITRATING|ESCALATED; was " + status);
        }
        if (ruling == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "ruling is required");
        }
        return new DisputeModel(id, taskId, raisedBy, reasonCategory, DisputeStatus.RULED,
                append(ruling), correlationId, createdAt, resolvedAt, acceptance);
    }

    /** RULED → RESOLVED. */
    public DisputeModel resolve() {
        requireStatus(DisputeStatus.RULED, "resolve");
        return new DisputeModel(id, taskId, raisedBy, reasonCategory, DisputeStatus.RESOLVED,
                rulings, correlationId, createdAt, Instant.now(), acceptance);
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
                append(fallbackRuling), correlationId, createdAt, Instant.now(), acceptance);
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
    public Acceptance acceptance() { return acceptance; }
    public Instant clientAcceptedAt() { return acceptance.clientAcceptedAt(); }
    public Instant builderAcceptedAt() { return acceptance.builderAcceptedAt(); }
    public DisputeParty appealedBy() { return acceptance.appealedBy(); }
    public Instant appealedAt() { return acceptance.appealedAt(); }
}
