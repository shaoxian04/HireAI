package com.hireai.domain.biz.apikey.service;

import com.hireai.domain.biz.apikey.model.ApiKeyStatus;
import com.hireai.domain.biz.apikey.model.IssuedApiKey;
import com.hireai.domain.biz.apikey.service.impl.ApiKeyIssueDomainServiceImpl;
import com.hireai.utility.hash.Sha256;
import org.junit.jupiter.api.Test;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyIssueDomainServiceImplTest {
    private final ApiKeyIssueDomainService svc =
            new ApiKeyIssueDomainServiceImpl(new SecureRandom());

    @Test
    void mintsPrefixedRawKeyStoredOnlyAsHashAndPrefix() {
        Instant now = Instant.parse("2026-07-15T10:00:00Z");
        IssuedApiKey issued = svc.issue(UUID.randomUUID(), "ci-bot", null, null, now);

        assertThat(issued.rawKey()).startsWith("hk_live_");
        assertThat(issued.rawKey().length()).isGreaterThan(20);
        // stored hash equals SHA-256 of the raw key; raw key is NOT recoverable from the model
        assertThat(issued.model().keyHash()).isEqualTo(Sha256.hex(issued.rawKey()));
        assertThat(issued.model().displayPrefix()).isEqualTo(issued.rawKey().substring(0, 14));
        assertThat(issued.model().status()).isEqualTo(ApiKeyStatus.ACTIVE);
        assertThat(issued.model().createdAt()).isEqualTo(now);
    }

    @Test
    void twoKeysDifferInRawAndHash() {
        Instant now = Instant.parse("2026-07-15T10:00:00Z");
        IssuedApiKey a = svc.issue(UUID.randomUUID(), "a", null, null, now);
        IssuedApiKey b = svc.issue(UUID.randomUUID(), "b", null, null, now);
        assertThat(a.rawKey()).isNotEqualTo(b.rawKey());
        assertThat(a.model().keyHash()).isNotEqualTo(b.model().keyHash());
    }
}
