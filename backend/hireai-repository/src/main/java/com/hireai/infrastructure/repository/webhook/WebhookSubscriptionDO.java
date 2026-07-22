package com.hireai.infrastructure.repository.webhook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** JPA persistence entity for a client_webhook_subscriptions row. */
@Entity
@Table(name = "client_webhook_subscriptions")
public class WebhookSubscriptionDO {

    @Id @Column(name = "id") private UUID id;
    @Column(name = "api_key_id", nullable = false) private UUID apiKeyId;
    @Column(name = "owner_id", nullable = false) private UUID ownerId;
    @Column(name = "callback_url", nullable = false) private String callbackUrl;
    @Column(name = "signing_secret", nullable = false) private String signingSecret;
    @Column(name = "active", nullable = false) private boolean active;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected WebhookSubscriptionDO() {}

    public WebhookSubscriptionDO(UUID id, UUID apiKeyId, UUID ownerId, String callbackUrl,
            String signingSecret, boolean active, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.apiKeyId = apiKeyId;
        this.ownerId = ownerId;
        this.callbackUrl = callbackUrl;
        this.signingSecret = signingSecret;
        this.active = active;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() { return id; }
    public UUID getApiKeyId() { return apiKeyId; }
    public UUID getOwnerId() { return ownerId; }
    public String getCallbackUrl() { return callbackUrl; }
    public String getSigningSecret() { return signingSecret; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
