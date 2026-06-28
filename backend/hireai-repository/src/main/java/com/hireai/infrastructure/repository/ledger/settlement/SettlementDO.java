package com.hireai.infrastructure.repository.ledger.settlement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** JPA persistence entity for a settlement row. */
@Entity
@Table(name = "settlements")
public class SettlementDO {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "task_id", nullable = false, unique = true)
    private UUID taskId;

    @Column(name = "type", nullable = false)
    private String type;

    @Column(name = "net", nullable = false)
    private BigDecimal net;

    @Column(name = "commission", nullable = false)
    private BigDecimal commission;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SettlementDO() {
    }

    public SettlementDO(UUID id, UUID taskId, String type, BigDecimal net,
                        BigDecimal commission, Instant createdAt) {
        this.id = id;
        this.taskId = taskId;
        this.type = type;
        this.net = net;
        this.commission = commission;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getTaskId() { return taskId; }
    public String getType() { return type; }
    public BigDecimal getNet() { return net; }
    public BigDecimal getCommission() { return commission; }
    public Instant getCreatedAt() { return createdAt; }
}
