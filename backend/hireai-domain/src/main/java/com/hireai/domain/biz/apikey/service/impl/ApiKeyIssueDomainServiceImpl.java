package com.hireai.domain.biz.apikey.service.impl;

import com.hireai.domain.biz.apikey.model.ApiKeyModel;
import com.hireai.domain.biz.apikey.model.IssuedApiKey;
import com.hireai.domain.biz.apikey.service.ApiKeyIssueDomainService;
import com.hireai.utility.hash.Sha256;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Raw key = {@code hk_live_} + 32 bytes of {@link SecureRandom} in URL-safe base64 (no padding).
 * Stored: SHA-256 hex of the raw key + the first 14 chars as a display prefix. The raw key is
 * returned once inside {@link IssuedApiKey} and never persisted.
 */
public class ApiKeyIssueDomainServiceImpl implements ApiKeyIssueDomainService {

    private static final String PREFIX = "hk_live_";
    private static final int PREFIX_DISPLAY_LEN = 14;
    private static final int RANDOM_BYTES = 32;

    private final SecureRandom random;

    public ApiKeyIssueDomainServiceImpl(SecureRandom random) {
        this.random = random;
    }

    @Override
    public IssuedApiKey issue(UUID userId, String name, BigDecimal spendCap,
                              BigDecimal dailySpendCap, Instant now) {
        byte[] bytes = new byte[RANDOM_BYTES];
        random.nextBytes(bytes);
        String rawKey = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String keyHash = Sha256.hex(rawKey);
        String displayPrefix = rawKey.substring(0, PREFIX_DISPLAY_LEN);
        ApiKeyModel model = ApiKeyModel.issue(userId, keyHash, displayPrefix, name,
                spendCap, dailySpendCap, now);
        return new IssuedApiKey(model, rawKey);
    }
}
