package com.hireai.infrastructure.repository.webhook;

import com.hireai.domain.biz.webhook.model.WebhookSubscriptionModel;
import com.hireai.domain.biz.webhook.repository.WebhookSubscriptionRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class WebhookSubscriptionRepositoryImpl implements WebhookSubscriptionRepository {

    private final WebhookSubscriptionJpaRepository jpa;

    public WebhookSubscriptionRepositoryImpl(WebhookSubscriptionJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public WebhookSubscriptionModel save(WebhookSubscriptionModel s) {
        jpa.save(new WebhookSubscriptionDO(s.id(), s.apiKeyId(), s.ownerId(), s.callbackUrl(),
                s.signingSecret(), s.active(), s.createdAt(), s.updatedAt()));
        return s;
    }

    @Override
    public Optional<WebhookSubscriptionModel> findActiveByApiKeyId(UUID apiKeyId) {
        return jpa.findByApiKeyIdAndActiveTrue(apiKeyId).map(this::toModel);
    }

    @Override
    public Optional<WebhookSubscriptionModel> findActiveByOwnerId(UUID ownerId) {
        return jpa.findByOwnerIdAndActiveTrue(ownerId).map(this::toModel);
    }

    @Override
    public Optional<WebhookSubscriptionModel> findById(UUID id) {
        return jpa.findById(id).map(this::toModel);
    }

    private WebhookSubscriptionModel toModel(WebhookSubscriptionDO d) {
        return WebhookSubscriptionModel.rehydrate(d.getId(), d.getApiKeyId(), d.getOwnerId(),
                d.getCallbackUrl(), d.getSigningSecret(), d.isActive(), d.getCreatedAt(), d.getUpdatedAt());
    }
}
