package com.hireai.domain.biz.ledger.wallet.service;

import com.hireai.domain.biz.ledger.wallet.enums.LedgerEntryType;
import com.hireai.domain.biz.ledger.wallet.info.SettlementBreakdown;
import com.hireai.domain.biz.ledger.wallet.model.LedgerEntryModel;
import com.hireai.domain.biz.ledger.wallet.model.WalletModel;
import com.hireai.domain.biz.ledger.wallet.service.impl.SettlementDomainServiceImpl;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import com.hireai.utility.exception.DomainException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deterministic settlement arithmetic (Invariant #3): accept -> 85/15 split out of the
 * client's escrow + payout credit to the builder; reject -> full refund. Every movement
 * appends a ledger entry through the aggregate (Invariant #2).
 */
class SettlementDomainServiceImplTest {

    private final SettlementDomainService service = new SettlementDomainServiceImpl();
    private final UUID taskId = UUID.randomUUID();

    /** Client wallet with `budget` already frozen in escrow, pending entries cleared. */
    private WalletModel clientWalletWithEscrow(String budget) {
        WalletModel w = WalletModel.openFor(UUID.randomUUID());
        w.topUp(Money.of("100.00"), "setup");
        w.freeze(Money.of(budget), taskId, "setup");
        w.clearPendingEntries();
        return w;
    }

    @Test
    void acceptanceSplitsEscrowEightyFifteen() {
        WalletModel client = clientWalletWithEscrow("20.00");
        WalletModel builder = WalletModel.openFor(UUID.randomUUID());

        SettlementBreakdown b = service.settleAcceptance(client, builder, Money.of("20.00"), taskId, "settle-x");

        assertThat(b.net()).isEqualTo(Money.of("17.00"));
        assertThat(b.commission()).isEqualTo(Money.of("3.00"));
        assertThat(client.escrow()).isEqualTo(Money.ZERO);
        assertThat(client.available()).isEqualTo(Money.of("80.00"));
        assertThat(builder.available()).isEqualTo(Money.of("17.00"));

        List<LedgerEntryType> clientTypes = client.pendingEntries().stream().map(LedgerEntryModel::type).toList();
        assertThat(clientTypes).containsExactly(LedgerEntryType.PAYOUT, LedgerEntryType.COMMISSION);
        List<LedgerEntryType> builderTypes = builder.pendingEntries().stream().map(LedgerEntryModel::type).toList();
        assertThat(builderTypes).containsExactly(LedgerEntryType.PAYOUT);
    }

    @Test
    void commissionRoundsHalfUpAndAlwaysReconciles() {
        // 10.01 * 0.15 = 1.5015 -> 1.50; net 8.51; 1.50 + 8.51 = 10.01
        WalletModel client = clientWalletWithEscrow("10.01");
        SettlementBreakdown b = service.settleAcceptance(client, WalletModel.openFor(UUID.randomUUID()),
                Money.of("10.01"), taskId, "c");
        assertThat(b.commission()).isEqualTo(Money.of("1.50"));
        assertThat(b.net()).isEqualTo(Money.of("8.51"));
        assertThat(b.net().add(b.commission())).isEqualTo(Money.of("10.01"));

        // 0.10 * 0.15 = 0.015 -> 0.02 (HALF_UP); net 0.08
        WalletModel client2 = clientWalletWithEscrow("0.10");
        SettlementBreakdown b2 = service.settleAcceptance(client2, WalletModel.openFor(UUID.randomUUID()),
                Money.of("0.10"), taskId, "c2");
        assertThat(b2.commission()).isEqualTo(Money.of("0.02"));
        assertThat(b2.net()).isEqualTo(Money.of("0.08"));
    }

    @Test
    void zeroCommissionSkipsTheCommissionEntry() {
        // 0.01 * 0.15 = 0.0015 -> 0.00; the COMMISSION ledger entry must be skipped (amounts must be positive)
        WalletModel client = clientWalletWithEscrow("0.01");
        WalletModel builder = WalletModel.openFor(UUID.randomUUID());
        SettlementBreakdown b = service.settleAcceptance(client, builder, Money.of("0.01"), taskId, "c");
        assertThat(b.commission()).isEqualTo(Money.ZERO);
        assertThat(b.net()).isEqualTo(Money.of("0.01"));
        assertThat(client.pendingEntries()).hasSize(1); // PAYOUT only
        assertThat(builder.available()).isEqualTo(Money.of("0.01"));
    }

    @Test
    void rejectionRefundsTheFullBudget() {
        WalletModel client = clientWalletWithEscrow("20.00");
        service.settleRejection(client, Money.of("20.00"), taskId, "settle-r");
        assertThat(client.escrow()).isEqualTo(Money.ZERO);
        assertThat(client.available()).isEqualTo(Money.of("100.00"));
        assertThat(client.pendingEntries()).hasSize(1);
        assertThat(client.pendingEntries().get(0).type()).isEqualTo(LedgerEntryType.REFUND);
    }

    @Test
    void commissionAppearsAtTheFlipPointBudget() {
        // 0.03 * 0.15 = 0.0045 -> 0.00 (skip COMMISSION); 0.04 * 0.15 = 0.006 -> 0.01 (emit it)
        WalletModel atSkip = clientWalletWithEscrow("0.03");
        SettlementBreakdown skip = service.settleAcceptance(atSkip, WalletModel.openFor(UUID.randomUUID()),
                Money.of("0.03"), taskId, "c");
        assertThat(skip.commission()).isEqualTo(Money.ZERO);
        assertThat(atSkip.pendingEntries()).hasSize(1);

        WalletModel atEmit = clientWalletWithEscrow("0.04");
        SettlementBreakdown emit = service.settleAcceptance(atEmit, WalletModel.openFor(UUID.randomUUID()),
                Money.of("0.04"), taskId, "c");
        assertThat(emit.commission()).isEqualTo(Money.of("0.01"));
        assertThat(emit.net()).isEqualTo(Money.of("0.03"));
        assertThat(atEmit.pendingEntries()).hasSize(2);
    }

    @Test
    void settlementBeyondEscrowIsRejectedByTheAggregate() {
        WalletModel client = clientWalletWithEscrow("20.00");
        assertThatThrownBy(() -> service.settleAcceptance(client, WalletModel.openFor(UUID.randomUUID()),
                Money.of("25.00"), taskId, "c"))
                .isInstanceOf(DomainException.class);
    }
}
