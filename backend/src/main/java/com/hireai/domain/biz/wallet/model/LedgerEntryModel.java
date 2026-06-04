package com.hireai.domain.biz.wallet.model;

import com.hireai.domain.biz.wallet.enums.LedgerEntryType;
import com.hireai.domain.shared.model.Money;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable child entity of the {@link WalletModel} aggregate. One row per credit
 * movement. Never updated or deleted (enforced by DB triggers) — corrections are
 * compensating entries.
 *
 * {@code amount} is the signed delta applied to the wallet's available balance
 * ({@code balanceAfter} is the available balance after the movement), so the
 * available balance is reconstructable by replaying entries in order.
 */
public final class LedgerEntryModel {

    private final UUID id;
    private final LedgerEntryType type;
    private final Money amount;
    private final Money balanceAfter;
    private final UUID relatedTaskId; // nullable (e.g. TOPUP)
    private final String correlationId;
    private final Instant createdAt;

    public LedgerEntryModel(UUID id, LedgerEntryType type, Money amount, Money balanceAfter,
                            UUID relatedTaskId, String correlationId, Instant createdAt) {
        this.id = id;
        this.type = type;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.relatedTaskId = relatedTaskId;
        this.correlationId = correlationId;
        this.createdAt = createdAt;
    }

    public UUID id() { return id; }
    public LedgerEntryType type() { return type; }
    public Money amount() { return amount; }
    public Money balanceAfter() { return balanceAfter; }
    public UUID relatedTaskId() { return relatedTaskId; }
    public String correlationId() { return correlationId; }
    public Instant createdAt() { return createdAt; }
}
