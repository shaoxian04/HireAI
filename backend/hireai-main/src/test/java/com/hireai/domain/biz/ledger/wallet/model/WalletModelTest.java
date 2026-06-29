package com.hireai.domain.biz.ledger.wallet.model;

import com.hireai.utility.result.ResultCode;
import com.hireai.domain.biz.ledger.wallet.enums.LedgerEntryType;
import com.hireai.utility.exception.DomainException;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletModelTest {

    private WalletModel newWallet() {
        return WalletModel.openFor(UUID.randomUUID());
    }

    @Test
    void topUpIncreasesAvailableAndAppendsEntry() {
        WalletModel wallet = newWallet();
        wallet.topUp(Money.of("100.00"), "corr-1");

        assertThat(wallet.available()).isEqualTo(Money.of("100.00"));
        assertThat(wallet.pendingEntries()).hasSize(1);
        assertThat(wallet.pendingEntries().get(0).type()).isEqualTo(LedgerEntryType.TOPUP);
        assertThat(wallet.pendingEntries().get(0).balanceAfter()).isEqualTo(Money.of("100.00"));
    }

    @Test
    void freezeMovesAvailableIntoEscrow() {
        WalletModel wallet = newWallet();
        wallet.topUp(Money.of("100.00"), "corr-1");
        UUID taskId = UUID.randomUUID();

        wallet.freeze(Money.of("30.00"), taskId, "corr-2");

        assertThat(wallet.available()).isEqualTo(Money.of("70.00"));
        assertThat(wallet.escrow()).isEqualTo(Money.of("30.00"));
        assertThat(wallet.pendingEntries()).hasSize(2);
    }

    @Test
    void freezeBeyondAvailableIsRejected() {
        WalletModel wallet = newWallet();
        wallet.topUp(Money.of("20.00"), "corr-1");

        assertThatThrownBy(() -> wallet.freeze(Money.of("50.00"), UUID.randomUUID(), "corr-2"))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).resultCode())
                        .isEqualTo(ResultCode.INSUFFICIENT_BALANCE));

        assertThat(wallet.available()).isEqualTo(Money.of("20.00"));
        assertThat(wallet.escrow()).isEqualTo(Money.ZERO);
    }

    @Test
    void refundReturnsEscrowToAvailable() {
        WalletModel wallet = newWallet();
        wallet.topUp(Money.of("100.00"), "c1");
        UUID taskId = UUID.randomUUID();
        wallet.freeze(Money.of("40.00"), taskId, "c2");

        wallet.refund(Money.of("40.00"), taskId, "c3");

        assertThat(wallet.available()).isEqualTo(Money.of("100.00"));
        assertThat(wallet.escrow()).isEqualTo(Money.ZERO);
    }

    @Test
    void releaseRemovesEscrowWithoutTouchingAvailable() {
        WalletModel wallet = newWallet();
        wallet.topUp(Money.of("100.00"), "c1");
        UUID taskId = UUID.randomUUID();
        wallet.freeze(Money.of("40.00"), taskId, "c2");

        wallet.release(Money.of("40.00"), taskId, LedgerEntryType.PAYOUT, "c3");

        assertThat(wallet.escrow()).isEqualTo(Money.ZERO);
        assertThat(wallet.available()).isEqualTo(Money.of("60.00"));
    }

    @Test
    void nonPositiveAmountsAreRejected() {
        WalletModel wallet = newWallet();
        assertThatThrownBy(() -> wallet.topUp(Money.of("0.00"), "c1"))
                .isInstanceOf(DomainException.class);
    }
}
