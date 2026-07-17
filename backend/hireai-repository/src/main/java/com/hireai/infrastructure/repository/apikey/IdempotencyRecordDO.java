package com.hireai.infrastructure.repository.apikey;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** JPA persistence entity for an idempotency_keys row. */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyRecordDO {

    @Id @Column(name = "id") private UUID id;
    @Column(name = "owner_id", nullable = false) private UUID ownerId;
    @Column(name = "idempotency_key", nullable = false) private String idempotencyKey;
    @Column(name = "request_fingerprint", nullable = false) private String requestFingerprint;
    @Column(name = "task_id") private UUID taskId;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected IdempotencyRecordDO() {}

    public IdempotencyRecordDO(UUID id, UUID ownerId, String idempotencyKey,
                               String requestFingerprint, UUID taskId, Instant createdAt) {
        this.id = id;
        this.ownerId = ownerId;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.taskId = taskId;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getOwnerId() { return ownerId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public UUID getTaskId() { return taskId; }
    public Instant getCreatedAt() { return createdAt; }
}
