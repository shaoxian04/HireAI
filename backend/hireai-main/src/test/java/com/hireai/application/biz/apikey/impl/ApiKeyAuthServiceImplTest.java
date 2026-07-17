package com.hireai.application.biz.apikey.impl;

import com.hireai.application.biz.apikey.ApiKeyAuthService;
import com.hireai.application.biz.apikey.ApiKeyPrincipal;
import com.hireai.domain.biz.apikey.model.ApiKeyModel;
import com.hireai.domain.biz.apikey.repository.ApiKeyRepository;
import com.hireai.utility.hash.Sha256;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiKeyAuthServiceImplTest {

    private final ApiKeyRepository repo = mock(ApiKeyRepository.class);
    private final Instant fixed = Instant.parse("2026-07-15T10:00:00Z");
    private final ApiKeyAuthService svc =
            new ApiKeyAuthServiceImpl(repo, Clock.fixed(fixed, ZoneOffset.UTC));

    private ApiKeyModel activeKey(UUID user, UUID id, String rawKey, Instant lastUsed) {
        return ApiKeyModel.rehydrate(id, user, Sha256.hex(rawKey), "hk_live_a1b2c3", "bot",
                new BigDecimal("100.00"), new BigDecimal("500.00"),
                com.hireai.domain.biz.apikey.model.ApiKeyStatus.ACTIVE, lastUsed,
                fixed.minusSeconds(86400), null);
    }

    @Test
    void resolvesActiveKeyToPrincipalAndBumpsStaleLastUsed() {
        UUID user = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();
        String raw = "hk_live_secret";
        // last used > 1 min ago → bump
        when(repo.findActiveByHash(eq(Sha256.hex(raw))))
                .thenReturn(Optional.of(activeKey(user, keyId, raw, fixed.minusSeconds(3600))));

        Optional<ApiKeyPrincipal> p = svc.authenticate(raw);

        assertThat(p).isPresent();
        assertThat(p.get().userId()).isEqualTo(user);
        assertThat(p.get().keyId()).isEqualTo(keyId);
        assertThat(p.get().spendCap()).isEqualByComparingTo("100.00");
        assertThat(p.get().dailySpendCap()).isEqualByComparingTo("500.00");
        verify(repo).touchLastUsed(eq(keyId), eq(fixed));
    }

    @Test
    void doesNotBumpWhenRecentlyUsed() {
        UUID user = UUID.randomUUID();
        String raw = "hk_live_secret";
        when(repo.findActiveByHash(any()))
                .thenReturn(Optional.of(activeKey(user, UUID.randomUUID(), raw, fixed.minusSeconds(5))));
        svc.authenticate(raw);
        verify(repo, never()).touchLastUsed(any(), any());
    }

    @Test
    void unknownOrBlankKeyIsEmpty() {
        when(repo.findActiveByHash(any())).thenReturn(Optional.empty());
        assertThat(svc.authenticate("hk_live_nope")).isEmpty();
        assertThat(svc.authenticate("")).isEmpty();
        assertThat(svc.authenticate(null)).isEmpty();
    }
}
