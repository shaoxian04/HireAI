package com.hireai.infrastructure.repository.webhook;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** JPA persistence entity for a webhook_deliveries row (the outbox row for one delivery attempt). */
@Entity
@Table(name = "webhook_deliveries")
public class WebhookDeliveryDO {
    @Id @Column(name = "id") private UUID id;
    @Column(name = "task_id", nullable = false) private UUID taskId;
    @Column(name = "owner_id", nullable = false) private UUID ownerId;
    @Column(name = "subscription_id", nullable = false) private UUID subscriptionId;
    @Column(name = "event_type", nullable = false) private String eventType;
    @Column(name = "payload", nullable = false) private String payload;
    @Column(name = "target_url", nullable = false) private String targetUrl;
    @Column(name = "status", nullable = false) private String status;
    @Column(name = "attempts", nullable = false) private int attempts;
    @Column(name = "next_attempt_at", nullable = false) private Instant nextAttemptAt;
    @Column(name = "last_error") private String lastError;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "delivered_at") private Instant deliveredAt;

    protected WebhookDeliveryDO() {}
    public WebhookDeliveryDO(UUID id, UUID taskId, UUID ownerId, UUID subscriptionId, String eventType,
            String payload, String targetUrl, String status, int attempts, Instant nextAttemptAt,
            String lastError, Instant createdAt, Instant deliveredAt) {
        this.id = id; this.taskId = taskId; this.ownerId = ownerId; this.subscriptionId = subscriptionId;
        this.eventType = eventType; this.payload = payload; this.targetUrl = targetUrl; this.status = status;
        this.attempts = attempts; this.nextAttemptAt = nextAttemptAt; this.lastError = lastError;
        this.createdAt = createdAt; this.deliveredAt = deliveredAt;
    }
    public UUID getId() { return id; }
    public UUID getTaskId() { return taskId; }
    public UUID getOwnerId() { return ownerId; }
    public UUID getSubscriptionId() { return subscriptionId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public String getTargetUrl() { return targetUrl; }
    public String getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDeliveredAt() { return deliveredAt; }
}
