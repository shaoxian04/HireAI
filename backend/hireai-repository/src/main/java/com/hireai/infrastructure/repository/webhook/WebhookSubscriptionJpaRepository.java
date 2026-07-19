package com.hireai.infrastructure.repository.webhook;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WebhookSubscriptionJpaRepository extends JpaRepository<WebhookSubscriptionDO, UUID> {
    Optional<WebhookSubscriptionDO> findByApiKeyIdAndActiveTrue(UUID apiKeyId);
    Optional<WebhookSubscriptionDO> findByOwnerIdAndActiveTrue(UUID ownerId);
}
