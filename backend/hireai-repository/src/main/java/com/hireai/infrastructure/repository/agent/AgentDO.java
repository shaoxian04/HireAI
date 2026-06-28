package com.hireai.infrastructure.repository.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA persistence entity for an agent. Separate from the domain {@code AgentModel} so the
 * domain stays framework-free. {@code current_version_id} is a plain UUID (no FK).
 */
@Entity
@Table(name = "agents")
public class AgentDO {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "current_version_id")
    private UUID currentVersionId;

    @Column(name = "reputation_score", nullable = false)
    private BigDecimal reputationScore;

    @Column(name = "gmt_create", nullable = false)
    private Instant gmtCreate;

    protected AgentDO() {
    }

    public AgentDO(UUID id, UUID ownerId, String name, String status, UUID currentVersionId,
                          BigDecimal reputationScore, Instant gmtCreate) {
        this.id = id;
        this.ownerId = ownerId;
        this.name = name;
        this.status = status;
        this.currentVersionId = currentVersionId;
        this.reputationScore = reputationScore;
        this.gmtCreate = gmtCreate;
    }

    public UUID getId() { return id; }
    public UUID getOwnerId() { return ownerId; }
    public String getName() { return name; }
    public String getStatus() { return status; }
    public UUID getCurrentVersionId() { return currentVersionId; }
    public BigDecimal getReputationScore() { return reputationScore; }
    public Instant getGmtCreate() { return gmtCreate; }
}
