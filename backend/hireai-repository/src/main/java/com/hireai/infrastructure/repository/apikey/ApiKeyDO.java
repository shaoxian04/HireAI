package com.hireai.infrastructure.repository.apikey;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** JPA persistence entity for an api_keys row. */
@Entity
@Table(name = "api_keys")
public class ApiKeyDO {

    @Id @Column(name = "id") private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "key_hash", nullable = false) private String keyHash;
    @Column(name = "display_prefix", nullable = false) private String displayPrefix;
    @Column(name = "name") private String name;
    @Column(name = "spend_cap") private BigDecimal spendCap;
    @Column(name = "daily_spend_cap") private BigDecimal dailySpendCap;
    @Column(name = "status", nullable = false) private String status;
    @Column(name = "last_used_at") private Instant lastUsedAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "revoked_at") private Instant revokedAt;

    protected ApiKeyDO() {}

    public ApiKeyDO(UUID id, UUID userId, String keyHash, String displayPrefix, String name,
                    BigDecimal spendCap, BigDecimal dailySpendCap, String status,
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

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getKeyHash() { return keyHash; }
    public String getDisplayPrefix() { return displayPrefix; }
    public String getName() { return name; }
    public BigDecimal getSpendCap() { return spendCap; }
    public BigDecimal getDailySpendCap() { return dailySpendCap; }
    public String getStatus() { return status; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getRevokedAt() { return revokedAt; }
}
