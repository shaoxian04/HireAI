package com.hireai.infrastructure.repository.adjudication;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA persistence entity for one append-only ruling row in a dispute's history. Written only by
 * {@code DisputeRepositoryImpl} (insert-only — the table's triggers raise on UPDATE/DELETE).
 * The effective ruling of a dispute is the highest-tier row.
 */
@Entity
@Table(name = "dispute_rulings")
public class DisputeRulingDO {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "dispute_id", nullable = false)
    private UUID disputeId;

    @Column(name = "tier", nullable = false)
    private int tier;

    @Column(name = "decided_by", nullable = false)
    private String decidedBy;

    @Column(name = "category", nullable = false)
    private String category;

    @Column(name = "rationale")
    private String rationale;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    @Column(name = "gmt_create", nullable = false)
    private Instant gmtCreate;

    protected DisputeRulingDO() {
    }

    public DisputeRulingDO(UUID id, UUID disputeId, int tier, String decidedBy, String category,
                           String rationale, Instant decidedAt, Instant gmtCreate) {
        this.id = id;
        this.disputeId = disputeId;
        this.tier = tier;
        this.decidedBy = decidedBy;
        this.category = category;
        this.rationale = rationale;
        this.decidedAt = decidedAt;
        this.gmtCreate = gmtCreate;
    }

    public UUID getId() { return id; }
    public UUID getDisputeId() { return disputeId; }
    public int getTier() { return tier; }
    public String getDecidedBy() { return decidedBy; }
    public String getCategory() { return category; }
    public String getRationale() { return rationale; }
    public Instant getDecidedAt() { return decidedAt; }
    public Instant getGmtCreate() { return gmtCreate; }
}
