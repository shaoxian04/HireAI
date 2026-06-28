package com.hireai.domain.biz.ledger.wallet.model;

import com.hireai.utility.result.ResultCode;
import com.hireai.domain.biz.ledger.wallet.enums.LedgerEntryType;
import com.hireai.utility.exception.DomainException;
import com.hireai.domain.shared.model.Money;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Wallet aggregate root. Owns the append-only ledger and guards the money
 * invariants:
 *   - available and escrow balances are never negative;
 *   - every balance change appends exactly one {@link LedgerEntryModel};
 *   - escrow only moves in via {@link #freeze} and out via {@link #release} /
 *     {@link #refund}.
 *
 * Behaviour lives here, not in setters. State transitions are driven by the
 * per-transition domain services, which delegate to these methods.
 *
 * {@code pendingEntries} collects ledger entries created during the current unit
 * of work so the repository can persist them; it is cleared after persistence.
 */
public final class WalletModel {

    private final UUID id;
    private final UUID userId;
    private Money available;
    private Money escrow;
    private final List<LedgerEntryModel> pendingEntries = new ArrayList<>();

    public WalletModel(UUID id, UUID userId, Money available, Money escrow) {
        this.id = id;
        this.userId = userId;
        this.available = available;
        this.escrow = escrow;
    }

    /** Factory for a brand-new, empty wallet. */
    public static WalletModel openFor(UUID userId) {
        return new WalletModel(UUID.randomUUID(), userId, Money.ZERO, Money.ZERO);
    }

    /** Client adds credits to the available balance. */
    public void topUp(Money amount, String correlationId) {
        requirePositive(amount);
        available = available.add(amount);
        append(LedgerEntryType.TOPUP, amount, null, correlationId);
    }

    /** Move credits from available to escrow at task submission. */
    public void freeze(Money amount, UUID taskId, String correlationId) {
        requirePositive(amount);
        if (amount.isGreaterThan(available)) {
            throw new DomainException(ResultCode.INSUFFICIENT_BALANCE,
                    "Available balance " + available + " is less than requested freeze " + amount);
        }
        available = available.subtract(amount);
        escrow = escrow.add(amount);
        append(LedgerEntryType.ESCROW_FREEZE, amount.negated(), taskId, correlationId);
    }

    /**
     * Release escrowed credits out of this wallet (Agent payout / commission).
     * The ledger entry records the released amount with an unchanged available balance —
     * see {@link LedgerEntryModel} for the reconstruction semantics.
     */
    public void release(Money amount, UUID taskId, LedgerEntryType type, String correlationId) {
        requireType(type, LedgerEntryType.PAYOUT, LedgerEntryType.COMMISSION, LedgerEntryType.SPLIT);
        requirePositive(amount);
        if (amount.isGreaterThan(escrow)) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Escrow " + escrow + " is less than requested release " + amount);
        }
        escrow = escrow.subtract(amount);
        // Money leaves the wallet entirely; available is unchanged.
        append(type, Money.ZERO, taskId, correlationId, amount);
    }

    /** Return escrowed credits to the client's available balance. */
    public void refund(Money amount, UUID taskId, String correlationId) {
        requirePositive(amount);
        if (amount.isGreaterThan(escrow)) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Escrow " + escrow + " is less than requested refund " + amount);
        }
        escrow = escrow.subtract(amount);
        available = available.add(amount);
        append(LedgerEntryType.REFUND, amount, taskId, correlationId);
    }

    /** Credit a payout into the available balance (Agent's wallet receiving funds). */
    public void credit(Money amount, UUID taskId, LedgerEntryType type, String correlationId) {
        requireType(type, LedgerEntryType.PAYOUT, LedgerEntryType.SPLIT);
        requirePositive(amount);
        available = available.add(amount);
        append(type, amount, taskId, correlationId);
    }

    private void append(LedgerEntryType type, Money delta, UUID taskId, String correlationId) {
        append(type, delta, taskId, correlationId, delta);
    }

    private void append(LedgerEntryType type, Money availableDelta, UUID taskId,
                        String correlationId, Money recordedAmount) {
        pendingEntries.add(new LedgerEntryModel(
                UUID.randomUUID(), type, recordedAmount, available, taskId, correlationId, Instant.now()));
    }

    private void requirePositive(Money amount) {
        if (amount == null || !amount.isPositive()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Amount must be positive");
        }
    }

    private void requireType(LedgerEntryType actual, LedgerEntryType... allowed) {
        for (LedgerEntryType t : allowed) {
            if (t == actual) return;
        }
        throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION, "Illegal ledger entry type " + actual);
    }

    public UUID id() { return id; }
    public UUID userId() { return userId; }
    public Money available() { return available; }
    public Money escrow() { return escrow; }

    public List<LedgerEntryModel> pendingEntries() {
        return Collections.unmodifiableList(pendingEntries);
    }

    public void clearPendingEntries() {
        pendingEntries.clear();
    }
}
