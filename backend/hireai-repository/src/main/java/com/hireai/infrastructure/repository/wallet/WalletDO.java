package com.hireai.infrastructure.repository.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * JPA persistence entity for a wallet. Deliberately separate from the domain
 * {@code WalletModel} so the domain stays framework-free; the repository impl
 * maps between the two.
 */
@Entity
@Table(name = "wallets")
public class WalletDO {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(name = "available_balance", nullable = false)
    private BigDecimal availableBalance;

    @Column(name = "escrow_balance", nullable = false)
    private BigDecimal escrowBalance;

    protected WalletDO() {
    }

    public WalletDO(UUID id, UUID userId, BigDecimal availableBalance, BigDecimal escrowBalance) {
        this.id = id;
        this.userId = userId;
        this.availableBalance = availableBalance;
        this.escrowBalance = escrowBalance;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public BigDecimal getAvailableBalance() { return availableBalance; }
    public BigDecimal getEscrowBalance() { return escrowBalance; }

    public void setAvailableBalance(BigDecimal availableBalance) { this.availableBalance = availableBalance; }
    public void setEscrowBalance(BigDecimal escrowBalance) { this.escrowBalance = escrowBalance; }
}
