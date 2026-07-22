package com.hireai.domain.biz.webhook.repository;

import com.hireai.domain.biz.webhook.model.WebhookSubscriptionModel;
import java.util.Optional;
import java.util.UUID;

public interface WebhookSubscriptionRepository {
    WebhookSubscriptionModel save(WebhookSubscriptionModel sub);
    Optional<WebhookSubscriptionModel> findActiveByApiKeyId(UUID apiKeyId);
    Optional<WebhookSubscriptionModel> findActiveByOwnerId(UUID ownerId);
    Optional<WebhookSubscriptionModel> findById(UUID id);
}
