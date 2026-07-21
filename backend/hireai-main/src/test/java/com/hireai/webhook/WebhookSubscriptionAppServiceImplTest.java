package com.hireai.webhook;

import com.hireai.application.biz.webhook.impl.WebhookSubscriptionAppServiceImpl;
import com.hireai.application.port.webhook.WebhookUrlValidatorPort;
import com.hireai.domain.biz.apikey.model.ApiKeyModel;
import com.hireai.domain.biz.apikey.repository.ApiKeyRepository;
import com.hireai.domain.biz.webhook.model.WebhookSubscriptionModel;
import com.hireai.domain.biz.webhook.repository.WebhookSubscriptionRepository;
import com.hireai.domain.biz.webhook.service.WebhookSecretGenerator;
import com.hireai.utility.exception.DomainException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.*;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebhookSubscriptionAppServiceImplTest {
    private final WebhookSubscriptionRepository repo = mock(WebhookSubscriptionRepository.class);
    private final WebhookSecretGenerator secrets = mock(WebhookSecretGenerator.class);
    private final WebhookUrlValidatorPort validator = mock(WebhookUrlValidatorPort.class);
    private final ApiKeyRepository apiKeys = mock(ApiKeyRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-19T00:00:00Z"), ZoneOffset.UTC);
    private final WebhookSubscriptionAppServiceImpl svc =
            new WebhookSubscriptionAppServiceImpl(repo, secrets, validator, apiKeys, clock);

    private final UUID owner = UUID.randomUUID(), keyId = UUID.randomUUID();

    private void keyOwnedBy(UUID ownerId) {
        ApiKeyModel k = mock(ApiKeyModel.class);
        when(k.userId()).thenReturn(ownerId);
        when(apiKeys.findById(keyId)).thenReturn(Optional.of(k));
    }

    @Test void registerValidatesUrlAssertsOwnershipAndReturnsSecret() {
        keyOwnedBy(owner);
        when(secrets.generate()).thenReturn("whsec_new");
        when(repo.findActiveByApiKeyId(keyId)).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        WebhookSubscriptionModel out = svc.register(owner, keyId, "https://c/cb");

        verify(validator).assertDeliverable("https://c/cb");
        assertThat(out.signingSecret()).isEqualTo("whsec_new");
        assertThat(out.callbackUrl()).isEqualTo("https://c/cb");
        assertThat(out.active()).isTrue();
    }

    @Test void registerRejectsForeignKey() {
        keyOwnedBy(UUID.randomUUID()); // someone else's key
        assertThatThrownBy(() -> svc.register(owner, keyId, "https://c/cb"))
                .isInstanceOf(DomainException.class);
        verify(repo, never()).save(any());
    }

    @Test void registerDeactivatesExistingActiveForKey() {
        keyOwnedBy(owner);
        when(secrets.generate()).thenReturn("whsec_new");
        WebhookSubscriptionModel existing = WebhookSubscriptionModel.create(UUID.randomUUID(), keyId, owner,
                "https://old", "whsec_old", clock.instant());
        when(repo.findActiveByApiKeyId(keyId)).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        svc.register(owner, keyId, "https://c/cb");

        // saved twice: the deactivated old + the new active
        verify(repo, times(2)).save(any());
    }

    @Test void registerRejectsPrivateUrlAndPersistsNothing() {
        keyOwnedBy(owner); // ownership passes → this isolates the Inv #6 SSRF gate, not the owner gate
        doThrow(new DomainException(com.hireai.utility.result.ResultCode.VALIDATION_ERROR, "private"))
                .when(validator).assertDeliverable("https://169.254.169.254/cb");
        assertThatThrownBy(() -> svc.register(owner, keyId, "https://169.254.169.254/cb"))
                .isInstanceOf(DomainException.class);
        verify(repo, never()).save(any()); // Inv #6: nothing persisted when the callback URL is rejected
    }

    @Test void rotateSecretReplacesSecret() {
        keyOwnedBy(owner);
        WebhookSubscriptionModel active = WebhookSubscriptionModel.create(UUID.randomUUID(), keyId, owner,
                "https://c/cb", "whsec_old", clock.instant());
        when(repo.findActiveByApiKeyId(keyId)).thenReturn(Optional.of(active));
        when(secrets.generate()).thenReturn("whsec_rotated");
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        WebhookSubscriptionModel out = svc.rotateSecret(owner, keyId);
        assertThat(out.signingSecret()).isEqualTo("whsec_rotated");
    }

    @Test void registerRejectsMissingKey() {
        when(apiKeys.findById(keyId)).thenReturn(Optional.empty()); // no such key at all
        assertThatThrownBy(() -> svc.register(owner, keyId, "https://c/cb"))
                .isInstanceOf(DomainException.class);
        verify(repo, never()).save(any());
    }

    @Test void getReturnsActiveSubscriptionForOwnedKey() {
        keyOwnedBy(owner);
        WebhookSubscriptionModel active = WebhookSubscriptionModel.create(UUID.randomUUID(), keyId, owner,
                "https://c/cb", "whsec_x", clock.instant());
        when(repo.findActiveByApiKeyId(keyId)).thenReturn(Optional.of(active));

        WebhookSubscriptionModel out = svc.get(owner, keyId);

        assertThat(out).isSameAs(active);
    }

    @Test void getRejectsForeignKey() {
        keyOwnedBy(UUID.randomUUID()); // someone else's key
        assertThatThrownBy(() -> svc.get(owner, keyId))
                .isInstanceOf(DomainException.class);
        verify(repo, never()).findActiveByApiKeyId(any());
    }

    @Test void getRejectsMissingKey() {
        when(apiKeys.findById(keyId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> svc.get(owner, keyId))
                .isInstanceOf(DomainException.class);
        verify(repo, never()).findActiveByApiKeyId(any());
    }

    @Test void rotateSecretRejectsForeignKey() {
        keyOwnedBy(UUID.randomUUID()); // someone else's key
        assertThatThrownBy(() -> svc.rotateSecret(owner, keyId))
                .isInstanceOf(DomainException.class);
        verify(repo, never()).save(any());
    }

    @Test void rotateSecretRejectsMissingKey() {
        when(apiKeys.findById(keyId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> svc.rotateSecret(owner, keyId))
                .isInstanceOf(DomainException.class);
        verify(repo, never()).save(any());
    }

    @Test void deactivateFlipsActiveToFalse() {
        keyOwnedBy(owner);
        WebhookSubscriptionModel active = WebhookSubscriptionModel.create(UUID.randomUUID(), keyId, owner,
                "https://c/cb", "whsec_x", clock.instant());
        when(repo.findActiveByApiKeyId(keyId)).thenReturn(Optional.of(active));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        svc.deactivate(owner, keyId);

        ArgumentCaptor<WebhookSubscriptionModel> captor = ArgumentCaptor.forClass(WebhookSubscriptionModel.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().active()).isFalse();
    }

    @Test void deactivateRejectsForeignKey() {
        keyOwnedBy(UUID.randomUUID()); // someone else's key
        assertThatThrownBy(() -> svc.deactivate(owner, keyId))
                .isInstanceOf(DomainException.class);
        verify(repo, never()).save(any());
    }

    @Test void deactivateRejectsMissingKey() {
        when(apiKeys.findById(keyId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> svc.deactivate(owner, keyId))
                .isInstanceOf(DomainException.class);
        verify(repo, never()).save(any());
    }
}
