package com.hireai.infrastructure.repository.reputation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.hireai.domain.biz.reputation.enums.ReputationEventType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA persistence entity for the append-only reputation_events table. Separate from the domain
 * model so the domain stays framework-free.
 *
 * <p>There is intentionally no setter and no update path: the database triggers refuse UPDATE and
 * DELETE outright (V27), so an entity that looked mutable would only produce a runtime exception
 * further from the mistake.
 */
@Entity
@Table(name = "reputation_events")
public class ReputationEventDO {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "task_id")
    private UUID taskId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private ReputationEventType eventType;

    @Column(name = "quality", nullable = false)
    private BigDecimal quality;

    @Column(name = "weight", nullable = false)
    private BigDecimal weight;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected ReputationEventDO() {
    }

    public ReputationEventDO(UUID id, UUID agentId, UUID taskId, ReputationEventType eventType,
                             BigDecimal quality, BigDecimal weight, Instant occurredAt) {
        this.id = id;
        this.agentId = agentId;
        this.taskId = taskId;
        this.eventType = eventType;
        this.quality = quality;
        this.weight = weight;
        this.occurredAt = occurredAt;
    }

    public UUID getId() { return id; }
    public UUID getAgentId() { return agentId; }
    public UUID getTaskId() { return taskId; }
    public ReputationEventType getEventType() { return eventType; }
    public BigDecimal getQuality() { return quality; }
    public BigDecimal getWeight() { return weight; }
    public Instant getOccurredAt() { return occurredAt; }
}
