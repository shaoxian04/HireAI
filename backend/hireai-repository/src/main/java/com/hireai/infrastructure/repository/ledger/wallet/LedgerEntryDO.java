package com.hireai.infrastructure.repository.ledger.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.hireai.domain.biz.ledger.wallet.enums.LedgerEntryType;

/**
 * JPA persistence entity for an append-only ledger row. The application never
 * issues UPDATE/DELETE against this table; DB triggers enforce append-only.
 */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntryDO {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false)
    private LedgerEntryType entryType;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false)
    private BigDecimal balanceAfter;

    @Column(name = "related_task_id")
    private UUID relatedTaskId;

    @Column(name = "correlation_id")
    private String correlationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerEntryDO() {
    }

    public LedgerEntryDO(UUID id, UUID walletId, LedgerEntryType entryType, BigDecimal amount,
                                BigDecimal balanceAfter, UUID relatedTaskId, String correlationId,
                                Instant createdAt) {
        this.id = id;
        this.walletId = walletId;
        this.entryType = entryType;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.relatedTaskId = relatedTaskId;
        this.correlationId = correlationId;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getWalletId() { return walletId; }
    public LedgerEntryType getEntryType() { return entryType; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public UUID getRelatedTaskId() { return relatedTaskId; }
    public String getCorrelationId() { return correlationId; }
    public Instant getCreatedAt() { return createdAt; }
}
