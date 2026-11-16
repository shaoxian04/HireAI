package com.hireai.domain.biz.ledger.settlement;

import com.hireai.domain.biz.adjudication.enums.DisputeParty;
import com.hireai.domain.biz.ledger.settlement.enums.DisputeFeeType;
import com.hireai.domain.biz.ledger.settlement.info.DisputeFeeCharge;
import com.hireai.domain.biz.ledger.settlement.service.impl.DisputeFeeDomainServiceImpl;
import com.hireai.domain.biz.ledger.wallet.enums.LedgerEntryType;
import com.hireai.domain.biz.ledger.wallet.model.LedgerEntryModel;
import com.hireai.domain.biz.ledger.wallet.model.WalletModel;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Loser-pays arbitration cost and Appeal Fee (spec D16/D18/D42/D43). */
class DisputeFeeTest {

    private final DisputeFeeDomainServiceImpl service = new DisputeFeeDomainServiceImpl();
    private final UUID taskId = UUID.randomUUID();

    private static WalletModel walletWith(String available) {
        return new WalletModel(UUID.randomUUID(), UUID.randomUUID(), Money.of(available), Money.ZERO);
    }

    @Test
    void theCapShareIsConfigurable() {
        // D41: a deployment may cap the loser's share at 10% instead of 20%.
        DisputeFeeDomainServiceImpl tenPercent = new DisputeFeeDomainServiceImpl(new java.math.BigDecimal("0.10"));
        DisputeFeeCharge charge = tenPercent.charge(DisputeFeeType.ARBITRATION_COST, Money.of("3.00"),
                Money.of("20.00"), EnumSet.of(DisputeParty.CLIENT), walletWith("50.00"), walletWith("0.00"), taskId, "c");
        assertThat(charge.clientCharged()).isEqualTo(Money.of("2.00"));
        assertThat(charge.platformCovered()).isEqualTo(Money.of("1.00"));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new DisputeFeeDomainServiceImpl(new java.math.BigDecimal("1.5")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theLosingClientPaysTheWholeCostFromItsBalance() {
        WalletModel client = walletWith("50.00");
        WalletModel builder = walletWith("0.00");

        DisputeFeeCharge charge = service.charge(DisputeFeeType.ARBITRATION_COST, Money.of("3.00"),
                Money.of("100.00"), EnumSet.of(DisputeParty.CLIENT), client, builder, taskId, "c");

        assertThat(charge.clientCharged()).isEqualTo(Money.of("3.00"));
        assertThat(charge.builderCharged()).isEqualTo(Money.ZERO);
        assertThat(charge.platformCovered()).isEqualTo(Money.ZERO);
        assertThat(client.available()).isEqualTo(Money.of("47.00"));
        LedgerEntryModel entry = client.pendingEntries().get(0);
        assertThat(entry.type()).isEqualTo(LedgerEntryType.ARBITRATION_FEE);
        assertThat(entry.amount()).isEqualTo(Money.of("-3.00"));
        assertThat(entry.balanceAfter()).isEqualTo(Money.of("47.00"));
        assertThat(entry.relatedTaskId()).isEqualTo(taskId);
        assertThat(builder.pendingEntries()).isEmpty();
    }

    @Test
    void theCostIsCappedAtTwentyPercentOfTheItemPriceAndThePlatformCoversTheExcess() {
        WalletModel client = walletWith("0.00");
        WalletModel builder = walletWith("40.00");

        DisputeFeeCharge charge = service.charge(DisputeFeeType.APPEAL_FEE, Money.of("5.00"),
                Money.of("10.00"), EnumSet.of(DisputeParty.BUILDER), client, builder, taskId, "c");

        assertThat(charge.cappedAmount()).isEqualTo(Money.of("2.00"));
        assertThat(charge.builderCharged()).isEqualTo(Money.of("2.00"));
        assertThat(charge.platformCovered()).isEqualTo(Money.of("3.00"));
        assertThat(builder.available()).isEqualTo(Money.of("38.00"));
        assertThat(builder.pendingEntries().get(0).type()).isEqualTo(LedgerEntryType.APPEAL_FEE);
    }

    @Test
    void aSplitRulingSharesTheCostEquallyBetweenBothSides() {
        WalletModel client = walletWith("10.00");
        WalletModel builder = walletWith("10.00");

        DisputeFeeCharge charge = service.charge(DisputeFeeType.ARBITRATION_COST, Money.of("3.01"),
                Money.of("100.00"), EnumSet.of(DisputeParty.CLIENT, DisputeParty.BUILDER), client, builder, taskId, "c");

        assertThat(charge.clientCharged()).isEqualTo(Money.of("1.51"));
        assertThat(charge.builderCharged()).isEqualTo(Money.of("1.50"));
        assertThat(charge.platformCovered()).isEqualTo(Money.ZERO);
    }

    @Test
    void aLoserWhoCannotPayIsChargedWhatItHasAndThePlatformCoversTheRest() {
        WalletModel client = walletWith("0.00");
        WalletModel builder = walletWith("1.25");

        DisputeFeeCharge charge = service.charge(DisputeFeeType.ARBITRATION_COST, Money.of("3.00"),
                Money.of("100.00"), EnumSet.of(DisputeParty.BUILDER), client, builder, taskId, "c");

        assertThat(charge.builderCharged()).isEqualTo(Money.of("1.25"));
        assertThat(charge.platformCovered()).isEqualTo(Money.of("1.75"));
        assertThat(builder.available()).isEqualTo(Money.ZERO);
    }

    @Test
    void aZeroCostChargesNothing() {
        WalletModel client = walletWith("10.00");
        WalletModel builder = walletWith("10.00");

        DisputeFeeCharge charge = service.charge(DisputeFeeType.ARBITRATION_COST, Money.ZERO,
                Money.of("100.00"), Set.of(DisputeParty.CLIENT), client, builder, taskId, "c");

        assertThat(charge.clientCharged()).isEqualTo(Money.ZERO);
        assertThat(client.pendingEntries()).isEmpty();
    }
}
