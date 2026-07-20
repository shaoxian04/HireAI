package com.hireai.application.biz.webhook.impl;

import com.hireai.application.biz.webhook.WebhookSubscriptionAppService;
import com.hireai.application.port.webhook.WebhookUrlValidatorPort;
import com.hireai.domain.biz.apikey.repository.ApiKeyRepository;
import com.hireai.domain.biz.webhook.model.WebhookSubscriptionModel;
import com.hireai.domain.biz.webhook.repository.WebhookSubscriptionRepository;
import com.hireai.domain.biz.webhook.service.WebhookSecretGenerator;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Owner-scoped management of a per-API-key webhook subscription (register/get/rotate/deactivate).
 * Every method resolves the API key via {@link ApiKeyRepository} and confirms it belongs to the
 * caller (Invariant #5) before touching the subscription — a foreign or missing key throws
 * {@code NOT_FOUND}, never a distinguishable "forbidden", so ownership never leaks. {@code register}
 * SSRF-checks the callback URL (Invariant #6) before persisting and deactivates any pre-existing
 * active subscription for the key first, so the {@code uq_webhook_sub_active_key} partial unique
 * index (at most one active subscription per key) is never violated.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class WebhookSubscriptionAppServiceImpl implements WebhookSubscriptionAppService {

    private final WebhookSubscriptionRepository repository;
    private final WebhookSecretGenerator secretGenerator;
    private final WebhookUrlValidatorPort urlValidator;
    private final ApiKeyRepository apiKeyRepository;
    private final Clock clock;

    @Override
    public WebhookSubscriptionModel register(UUID ownerId, UUID apiKeyId, String callbackUrl) {
        urlValidator.assertDeliverable(callbackUrl);
        assertKeyOwned(ownerId, apiKeyId);
        Instant now = clock.instant();
        repository.findActiveByApiKeyId(apiKeyId).ifPresent(s -> repository.save(s.deactivate(now)));
        WebhookSubscriptionModel sub = WebhookSubscriptionModel.create(
                UUID.randomUUID(), apiKeyId, ownerId, callbackUrl, secretGenerator.generate(), now);
        return repository.save(sub);
    }

    @Override
    @Transactional(readOnly = true)
    public WebhookSubscriptionModel get(UUID ownerId, UUID apiKeyId) {
        assertKeyOwned(ownerId, apiKeyId);
        return activeForKey(apiKeyId);
    }

    @Override
    public WebhookSubscriptionModel rotateSecret(UUID ownerId, UUID apiKeyId) {
        assertKeyOwned(ownerId, apiKeyId);
        WebhookSubscriptionModel active = activeForKey(apiKeyId);
        return repository.save(active.rotateSecret(secretGenerator.generate(), clock.instant()));
    }

    @Override
    public void deactivate(UUID ownerId, UUID apiKeyId) {
        assertKeyOwned(ownerId, apiKeyId);
        WebhookSubscriptionModel active = activeForKey(apiKeyId);
        repository.save(active.deactivate(clock.instant()));
    }

    private WebhookSubscriptionModel activeForKey(UUID apiKeyId) {
        return repository.findActiveByApiKeyId(apiKeyId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "No active webhook subscription"));
    }

    private void assertKeyOwned(UUID ownerId, UUID apiKeyId) {
        boolean owned = apiKeyRepository.findById(apiKeyId)
                .map(k -> k.userId().equals(ownerId)).orElse(false);
        if (!owned) throw new DomainException(ResultCode.NOT_FOUND, "API key not found: " + apiKeyId);
    }
}
