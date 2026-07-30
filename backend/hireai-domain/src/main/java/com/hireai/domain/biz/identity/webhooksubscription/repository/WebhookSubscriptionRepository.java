package com.hireai.domain.biz.identity.webhooksubscription.repository;

import com.hireai.domain.biz.identity.webhooksubscription.model.WebhookSubscriptionModel;
import java.util.Optional;
import java.util.UUID;

public interface WebhookSubscriptionRepository {
    WebhookSubscriptionModel save(WebhookSubscriptionModel sub);
    Optional<WebhookSubscriptionModel> findActiveByApiKeyId(UUID apiKeyId);
    Optional<WebhookSubscriptionModel> findActiveByOwnerId(UUID ownerId);
    Optional<WebhookSubscriptionModel> findById(UUID id);
}
