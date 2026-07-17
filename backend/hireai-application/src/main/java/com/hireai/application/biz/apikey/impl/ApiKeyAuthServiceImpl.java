package com.hireai.application.biz.apikey.impl;

import com.hireai.application.biz.apikey.ApiKeyAuthService;
import com.hireai.application.biz.apikey.ApiKeyPrincipal;
import com.hireai.domain.biz.apikey.model.ApiKeyModel;
import com.hireai.domain.biz.apikey.repository.ApiKeyRepository;
import com.hireai.utility.hash.Sha256;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Resolves a raw API key to a principal: hash → findActiveByHash → principal. Best-effort throttled
 * last_used_at bump (only when null or older than 1 minute) to avoid a write per request. Runs in
 * one short transaction (the read + optional update); called from the auth filter, which has none.
 */
@Service
@Slf4j
public class ApiKeyAuthServiceImpl implements ApiKeyAuthService {

    private static final Duration TOUCH_THROTTLE = Duration.ofMinutes(1);

    private final ApiKeyRepository repository;
    private final Clock clock;

    public ApiKeyAuthServiceImpl(ApiKeyRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Optional<ApiKeyPrincipal> authenticate(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return Optional.empty();
        }
        Optional<ApiKeyModel> found = repository.findActiveByHash(Sha256.hex(rawKey));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ApiKeyModel key = found.get();
        Instant now = clock.instant();
        if (key.lastUsedAt() == null || key.lastUsedAt().isBefore(now.minus(TOUCH_THROTTLE))) {
            repository.touchLastUsed(key.id(), now);
        }
        return Optional.of(new ApiKeyPrincipal(key.userId(), key.id(),
                key.spendCap(), key.dailySpendCap()));
    }
}
