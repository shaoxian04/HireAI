package com.hireai.domain.biz.webhook.model;

import com.hireai.domain.biz.webhook.WebhookBackoffPolicy;
import com.hireai.domain.biz.webhook.enums.WebhookDeliveryStatus;
import com.hireai.domain.biz.webhook.enums.WebhookEventType;
import java.time.Instant;
import java.util.UUID;

/**
 * Webhook delivery aggregate — one row per outbound event attempt (the outbox row).
 * Framework-free and IMMUTABLE: every transition returns a NEW copy.
 */
public final class WebhookDeliveryModel {
    private final UUID id, taskId, ownerId, subscriptionId;
    private final WebhookEventType eventType;
    private final String payload, targetUrl;
    private final WebhookDeliveryStatus status;
    private final int attempts;
    private final Instant nextAttemptAt, createdAt, deliveredAt;
    private final String lastError;

    private WebhookDeliveryModel(UUID id, UUID taskId, UUID ownerId, UUID subscriptionId,
            WebhookEventType eventType, String payload, String targetUrl, WebhookDeliveryStatus status,
            int attempts, Instant nextAttemptAt, String lastError, Instant createdAt, Instant deliveredAt) {
        this.id = id; this.taskId = taskId; this.ownerId = ownerId; this.subscriptionId = subscriptionId;
        this.eventType = eventType; this.payload = payload; this.targetUrl = targetUrl; this.status = status;
        this.attempts = attempts; this.nextAttemptAt = nextAttemptAt; this.lastError = lastError;
        this.createdAt = createdAt; this.deliveredAt = deliveredAt;
    }

    public static WebhookDeliveryModel enqueue(UUID id, UUID taskId, UUID ownerId, UUID subscriptionId,
            WebhookEventType eventType, String payload, String targetUrl, Instant now) {
        return new WebhookDeliveryModel(id, taskId, ownerId, subscriptionId, eventType, payload, targetUrl,
                WebhookDeliveryStatus.PENDING, 0, now, null, now, null);
    }

    public WebhookDeliveryModel markDelivered(Instant now) {
        return new WebhookDeliveryModel(id, taskId, ownerId, subscriptionId, eventType, payload, targetUrl,
                WebhookDeliveryStatus.DELIVERED, attempts + 1, nextAttemptAt, lastError, createdAt, now);
    }

    public WebhookDeliveryModel recordFailure(Instant now, String error, WebhookBackoffPolicy backoff) {
        int a = attempts + 1;
        boolean dead = backoff.exhausted(a);
        return new WebhookDeliveryModel(id, taskId, ownerId, subscriptionId, eventType, payload, targetUrl,
                dead ? WebhookDeliveryStatus.DEAD : WebhookDeliveryStatus.PENDING, a,
                dead ? nextAttemptAt : backoff.nextAttempt(a, now), error, createdAt, null);
    }

    public WebhookDeliveryModel requeue(Instant now) {
        return new WebhookDeliveryModel(id, taskId, ownerId, subscriptionId, eventType, payload, targetUrl,
                WebhookDeliveryStatus.PENDING, 0, now, null, createdAt, null);
    }

    public static WebhookDeliveryModel rehydrate(UUID id, UUID taskId, UUID ownerId, UUID subscriptionId,
            WebhookEventType eventType, String payload, String targetUrl, WebhookDeliveryStatus status,
            int attempts, Instant nextAttemptAt, String lastError, Instant createdAt, Instant deliveredAt) {
        return new WebhookDeliveryModel(id, taskId, ownerId, subscriptionId, eventType, payload, targetUrl,
                status, attempts, nextAttemptAt, lastError, createdAt, deliveredAt);
    }

    public UUID id() { return id; }
    public UUID taskId() { return taskId; }
    public UUID ownerId() { return ownerId; }
    public UUID subscriptionId() { return subscriptionId; }
    public WebhookEventType eventType() { return eventType; }
    public String payload() { return payload; }
    public String targetUrl() { return targetUrl; }
    public WebhookDeliveryStatus status() { return status; }
    public int attempts() { return attempts; }
    public Instant nextAttemptAt() { return nextAttemptAt; }
    public String lastError() { return lastError; }
    public Instant createdAt() { return createdAt; }
    public Instant deliveredAt() { return deliveredAt; }
}
