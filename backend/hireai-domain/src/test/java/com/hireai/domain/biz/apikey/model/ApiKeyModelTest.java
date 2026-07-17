package com.hireai.domain.biz.apikey.model;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyModelTest {
    @Test
    void rehydrateExposesFieldsAndActiveReflectsStatus() {
        Instant now = Instant.parse("2026-07-15T10:00:00Z");
        ApiKeyModel k = ApiKeyModel.rehydrate(UUID.randomUUID(), UUID.randomUUID(),
                "hash", "hk_live_a1b2c3", "ci-bot", null, null,
                ApiKeyStatus.ACTIVE, null, now, null);
        assertThat(k.isActive()).isTrue();
        assertThat(k.displayPrefix()).isEqualTo("hk_live_a1b2c3");
    }

    @Test
    void revokeTransitionsToRevokedAndStampsTime() {
        Instant now = Instant.parse("2026-07-15T10:00:00Z");
        ApiKeyModel k = ApiKeyModel.rehydrate(UUID.randomUUID(), UUID.randomUUID(),
                "hash", "hk_live_a1b2c3", "ci-bot", null, null,
                ApiKeyStatus.ACTIVE, null, now, null);
        ApiKeyModel revoked = k.revoke(Instant.parse("2026-07-15T11:00:00Z"));
        assertThat(revoked.status()).isEqualTo(ApiKeyStatus.REVOKED);
        assertThat(revoked.revokedAt()).isEqualTo(Instant.parse("2026-07-15T11:00:00Z"));
        assertThat(revoked.isActive()).isFalse();
        assertThat(k.isActive()).isTrue(); // original unchanged (immutability)
    }
}
