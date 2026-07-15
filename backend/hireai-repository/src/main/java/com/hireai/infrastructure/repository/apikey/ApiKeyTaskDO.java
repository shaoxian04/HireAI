package com.hireai.infrastructure.repository.apikey;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** JPA persistence entity for an api_key_task row (one per key-submitted task). */
@Entity
@Table(name = "api_key_task")
public class ApiKeyTaskDO {

    @Id @Column(name = "task_id") private UUID taskId;
    @Column(name = "api_key_id", nullable = false) private UUID apiKeyId;
    @Column(name = "budget", nullable = false) private BigDecimal budget;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected ApiKeyTaskDO() {}

    public ApiKeyTaskDO(UUID taskId, UUID apiKeyId, BigDecimal budget, Instant createdAt) {
        this.taskId = taskId;
        this.apiKeyId = apiKeyId;
        this.budget = budget;
        this.createdAt = createdAt;
    }

    public UUID getTaskId() { return taskId; }
    public UUID getApiKeyId() { return apiKeyId; }
    public BigDecimal getBudget() { return budget; }
    public Instant getCreatedAt() { return createdAt; }
}
