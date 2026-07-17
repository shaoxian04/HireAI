package com.hireai.application.biz.apikey.impl;

import com.hireai.application.biz.apikey.ApiKeyManagementAppService;
import com.hireai.domain.biz.apikey.model.ApiKeyModel;
import com.hireai.domain.biz.apikey.model.IssuedApiKey;
import com.hireai.domain.biz.apikey.repository.ApiKeyRepository;
import com.hireai.domain.biz.apikey.service.ApiKeyIssueDomainService;
import com.hireai.domain.biz.apikey.service.impl.ApiKeyIssueDomainServiceImpl;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiKeyManagementAppServiceImplTest {

    private final ApiKeyRepository repo = mock(ApiKeyRepository.class);
    private final ApiKeyIssueDomainService issue = new ApiKeyIssueDomainServiceImpl(new SecureRandom());
    private final Instant fixed = Instant.parse("2026-07-15T10:00:00Z");
    private final ApiKeyManagementAppService svc =
            new ApiKeyManagementAppServiceImpl(issue, repo, Clock.fixed(fixed, ZoneOffset.UTC));

    @Test
    void createIssuesAndPersistsAndReturnsRawOnce() {
        UUID owner = UUID.randomUUID();
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        IssuedApiKey issued = svc.create(owner, "ci-bot", null, null);
        assertThat(issued.rawKey()).startsWith("hk_live_");
        assertThat(issued.model().userId()).isEqualTo(owner);
        verify(repo).save(any(ApiKeyModel.class));
    }

    @Test
    void revokeOwnedKeyTransitionsAndSaves() {
        UUID owner = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();
        ApiKeyModel key = ApiKeyModel.issue(owner, "h", "hk_live_a1b2c3", "bot", null, null, fixed);
        when(repo.findById(keyId)).thenReturn(Optional.of(
                ApiKeyModel.rehydrate(keyId, owner, "h", "hk_live_a1b2c3", "bot", null, null,
                        com.hireai.domain.biz.apikey.model.ApiKeyStatus.ACTIVE, null, fixed, null)));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        ApiKeyModel revoked = svc.revoke(keyId, owner);
        assertThat(revoked.status()).isEqualTo(com.hireai.domain.biz.apikey.model.ApiKeyStatus.REVOKED);
        assertThat(revoked.revokedAt()).isEqualTo(fixed);
        verify(repo).save(argThat(
                k -> k.status() == com.hireai.domain.biz.apikey.model.ApiKeyStatus.REVOKED));
    }

    @Test
    void revokeNonOwnedKeyThrowsNotFoundAndDoesNotSave() {
        UUID keyId = UUID.randomUUID();
        when(repo.findById(keyId)).thenReturn(Optional.of(
                ApiKeyModel.rehydrate(keyId, UUID.randomUUID(), "h", "hk_live_a1b2c3", "bot", null, null,
                        com.hireai.domain.biz.apikey.model.ApiKeyStatus.ACTIVE, null, fixed, null)));

        assertThatThrownBy(() -> svc.revoke(keyId, UUID.randomUUID()))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).resultCode()).isEqualTo(ResultCode.NOT_FOUND));
        verify(repo, never()).save(any());
    }

    @Test
    void revokeMissingKeyThrowsNotFound() {
        when(repo.findById(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> svc.revoke(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).resultCode()).isEqualTo(ResultCode.NOT_FOUND));
        verify(repo, never()).save(any());
    }
}
