package com.hireai.domain.biz.ledger.wallet.model;

import com.hireai.domain.biz.ledger.wallet.enums.LedgerEntryType;
import com.hireai.domain.shared.model.Money;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable child entity of the {@link WalletModel} aggregate. One row per credit
 * movement. Never updated or deleted (enforced by DB triggers) — corrections are
 * compensating entries.
 *
 * <p><b>Per-type {@code amount} semantics:</b>
 * <ul>
 *   <li>{@code TOPUP}, {@code REFUND}, and credit-side {@code PAYOUT}/{@code SPLIT} rows:
 *       {@code amount} is the positive delta applied to the available balance.</li>
 *   <li>{@code ESCROW_FREEZE} rows: {@code amount} is recorded negated (the available
 *       balance decreases by {@code abs(amount)}).</li>
 *   <li>Escrow-release rows ({@code PAYOUT}/{@code COMMISSION}/{@code SPLIT} emitted by
 *       {@link WalletModel#release}): {@code amount} is the positive amount leaving escrow;
 *       the available balance is <em>unchanged</em>.</li>
 * </ul>
 *
 * <p>{@code balanceAfter} is ALWAYS the available-balance snapshot after the entry is
 * applied.
 *
 * <p><b>Reconstruction rule:</b> available = {@code balanceAfter} of the latest row (or
 * replay deltas branching on the rules above); escrow = replay of {@code +amount} for
 * {@code ESCROW_FREEZE} (absolute value) and {@code −amount} for release/refund rows.
 * A release-{@code PAYOUT} row is distinguishable from a credit-{@code PAYOUT} row on the
 * same wallet because a credit strictly increases {@code balanceAfter} by {@code amount}
 * while a release leaves it unchanged.
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
