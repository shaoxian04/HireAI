package com.hireai.domain.biz.webhook.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Webhook subscription aggregate — one per API key. Framework-free and IMMUTABLE:
 * every transition returns a NEW copy.
 */
public final class WebhookSubscriptionModel {
    private final UUID id, apiKeyId, ownerId;
    private final String callbackUrl, signingSecret;
    private final boolean active;
    private final Instant createdAt, updatedAt;

    private WebhookSubscriptionModel(UUID id, UUID apiKeyId, UUID ownerId, String callbackUrl,
            String signingSecret, boolean active, Instant createdAt, Instant updatedAt) {
        this.id = id; this.apiKeyId = apiKeyId; this.ownerId = ownerId; this.callbackUrl = callbackUrl;
        this.signingSecret = signingSecret; this.active = active; this.createdAt = createdAt; this.updatedAt = updatedAt;
    }

    public static WebhookSubscriptionModel create(UUID id, UUID apiKeyId, UUID ownerId, String callbackUrl,
            String secret, Instant now) {
        return new WebhookSubscriptionModel(id, apiKeyId, ownerId, callbackUrl, secret, true, now, now);
    }
    public WebhookSubscriptionModel rotateSecret(String newSecret, Instant now) {
        return new WebhookSubscriptionModel(id, apiKeyId, ownerId, callbackUrl, newSecret, active, createdAt, now);
    }
    public WebhookSubscriptionModel deactivate(Instant now) {
        return new WebhookSubscriptionModel(id, apiKeyId, ownerId, callbackUrl, signingSecret, false, createdAt, now);
    }
    public static WebhookSubscriptionModel rehydrate(UUID id, UUID apiKeyId, UUID ownerId, String callbackUrl,
            String signingSecret, boolean active, Instant createdAt, Instant updatedAt) {
        return new WebhookSubscriptionModel(id, apiKeyId, ownerId, callbackUrl, signingSecret, active, createdAt, updatedAt);
    }

    public UUID id() { return id; }
    public UUID apiKeyId() { return apiKeyId; }
    public UUID ownerId() { return ownerId; }
    public String callbackUrl() { return callbackUrl; }
    public String signingSecret() { return signingSecret; }
    public boolean active() { return active; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
