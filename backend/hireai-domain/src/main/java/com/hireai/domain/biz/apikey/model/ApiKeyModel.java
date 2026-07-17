package com.hireai.domain.biz.apikey.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * API-key aggregate root. Immutable; state transitions return new instances. The raw key is NEVER
 * held here — only its SHA-256 hash and a short display prefix. Issuance is performed by
 * {@code ApiKeyIssueDomainService} (which produces the raw key alongside the model, once).
 */
public final class ApiKeyModel {

    private final UUID id;
    private final UUID userId;
    private final String keyHash;
    private final String displayPrefix;
    private final String name;
    private final BigDecimal spendCap;       // nullable — max concurrent frozen escrow
    private final BigDecimal dailySpendCap;  // nullable — max committed per rolling 24h
    private final ApiKeyStatus status;
    private final Instant lastUsedAt;        // nullable
    private final Instant createdAt;
    private final Instant revokedAt;         // nullable

    private ApiKeyModel(UUID id, UUID userId, String keyHash, String displayPrefix, String name,
                        BigDecimal spendCap, BigDecimal dailySpendCap, ApiKeyStatus status,
                        Instant lastUsedAt, Instant createdAt, Instant revokedAt) {
        this.id = id;
        this.userId = userId;
        this.keyHash = keyHash;
        this.displayPrefix = displayPrefix;
        this.name = name;
        this.spendCap = spendCap;
        this.dailySpendCap = dailySpendCap;
        this.status = status;
        this.lastUsedAt = lastUsedAt;
        this.createdAt = createdAt;
        this.revokedAt = revokedAt;
    }

    /** Factory for a freshly minted, ACTIVE key. Called only by the issue domain service. */
    public static ApiKeyModel issue(UUID userId, String keyHash, String displayPrefix, String name,
                                     BigDecimal spendCap, BigDecimal dailySpendCap, Instant createdAt) {
        return new ApiKeyModel(UUID.randomUUID(), userId, keyHash, displayPrefix, name,
                spendCap, dailySpendCap, ApiKeyStatus.ACTIVE, null, createdAt, null);
    }

    public static ApiKeyModel rehydrate(UUID id, UUID userId, String keyHash, String displayPrefix,
                                        String name, BigDecimal spendCap, BigDecimal dailySpendCap,
                                        ApiKeyStatus status, Instant lastUsedAt, Instant createdAt,
                                        Instant revokedAt) {
        return new ApiKeyModel(id, userId, keyHash, displayPrefix, name, spendCap, dailySpendCap,
                status, lastUsedAt, createdAt, revokedAt);
    }

    public ApiKeyModel revoke(Instant now) {
        return new ApiKeyModel(id, userId, keyHash, displayPrefix, name, spendCap, dailySpendCap,
                ApiKeyStatus.REVOKED, lastUsedAt, createdAt, now);
    }

    public boolean isActive() { return status == ApiKeyStatus.ACTIVE; }

    public UUID id() { return id; }
    public UUID userId() { return userId; }
    public String keyHash() { return keyHash; }
    public String displayPrefix() { return displayPrefix; }
    public String name() { return name; }
    public BigDecimal spendCap() { return spendCap; }
    public BigDecimal dailySpendCap() { return dailySpendCap; }
    public ApiKeyStatus status() { return status; }
    public Instant lastUsedAt() { return lastUsedAt; }
    public Instant createdAt() { return createdAt; }
    public Instant revokedAt() { return revokedAt; }
}
