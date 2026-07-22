package com.hireai.application.biz.webhook;

import com.hireai.domain.biz.webhook.model.WebhookSubscriptionModel;
import java.util.UUID;

/**
 * Client-facing management of a per-API-key webhook subscription. Every method is owner-scoped:
 * {@code ownerId} must come from the JWT principal, and a foreign or missing {@code apiKeyId}
 * throws {@code NOT_FOUND} (never a distinguishable "forbidden") so ownership never leaks.
 */
public interface WebhookSubscriptionAppService {
    WebhookSubscriptionModel register(UUID ownerId, UUID apiKeyId, String callbackUrl);
    WebhookSubscriptionModel get(UUID ownerId, UUID apiKeyId);
    WebhookSubscriptionModel rotateSecret(UUID ownerId, UUID apiKeyId);
    void deactivate(UUID ownerId, UUID apiKeyId);
}
