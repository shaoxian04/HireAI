package com.hireai.domain.biz.ledger.settlement;

import com.hireai.domain.biz.ledger.settlement.info.SettlementBreakdown;
import com.hireai.domain.biz.ledger.settlement.service.SettlementPolicy;
import com.hireai.domain.biz.ledger.settlement.service.impl.SettlementDomainServiceImpl;
import com.hireai.domain.biz.ledger.wallet.model.WalletModel;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SettlementSplitTest {

    private final SettlementDomainServiceImpl service = new SettlementDomainServiceImpl();

    @Test
    void splitReconcilesToBudgetAndPaysHalfNet() {
        UUID client = UUID.randomUUID();
        UUID builder = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        WalletModel clientWallet = WalletModel.openFor(client);
        WalletModel builderWallet = WalletModel.openFor(builder);
        Money budget = Money.of("100.00");
        // client must hold the budget in escrow before settlement
        clientWallet.topUp(budget, "seed");
        clientWallet.freeze(budget, taskId, "freeze");

        SettlementBreakdown b = service.settleSplit(clientWallet, builderWallet, budget, taskId, "corr");

        // builderShare = 50.00; commission = 7.50; net = 42.50; clientRefund = 50.00
        assertThat(b.net()).isEqualTo(Money.of("42.50"));
        assertThat(b.commission()).isEqualTo(Money.of("7.50"));
        assertThat(builderWallet.available()).isEqualTo(Money.of("42.50"));
        assertThat(clientWallet.available()).isEqualTo(Money.of("50.00")); // the refunded half
        assertThat(clientWallet.escrow()).isEqualTo(Money.ZERO);            // escrow fully drained
        // reconciliation: net + commission + clientRefund == budget
        assertThat(b.net().add(b.commission()).add(Money.of("50.00"))).isEqualTo(budget);
    }

    @Test
    void splitReconcilesOnOddBudget() {
        UUID client = UUID.randomUUID();
        UUID builder = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        WalletModel clientWallet = WalletModel.openFor(client);
        WalletModel builderWallet = WalletModel.openFor(builder);
        Money budget = Money.of("5.01");
        clientWallet.topUp(budget, "seed");
        clientWallet.freeze(budget, taskId, "freeze");

        SettlementBreakdown b = service.settleSplit(clientWallet, builderWallet, budget, taskId, "corr");

        // builderShare = round(2.505) = 2.51; commission = round(0.3765) = 0.38; net = 2.13;
        // clientRefund = 5.01 - 2.51 = 2.50  → net+commission+refund = 2.13+0.38+2.50 = 5.01
        assertThat(b.net().add(b.commission())).isEqualTo(Money.of("2.51"));
        assertThat(clientWallet.escrow()).isEqualTo(Money.ZERO);
        assertThat(b.net().add(b.commission()).add(clientWallet.available())).isEqualTo(budget);
    }

    @Test
    void builderShareOnSplitIsHalfHalfUp() {
        assertThat(SettlementPolicy.builderShareOnSplit(Money.of("100.00"))).isEqualTo(Money.of("50.00"));
        assertThat(SettlementPolicy.builderShareOnSplit(Money.of("5.01"))).isEqualTo(Money.of("2.51"));
    }
}
