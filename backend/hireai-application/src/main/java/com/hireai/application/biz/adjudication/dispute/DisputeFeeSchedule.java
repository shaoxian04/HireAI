package com.hireai.application.biz.adjudication.dispute;

import com.hireai.domain.biz.ledger.settlement.service.SettlementPolicy;
import com.hireai.domain.shared.model.Money;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * The configured dispute costs (spec D16/D18/D42/D43) and how long a proposed ruling waits for
 * acceptance or appeal. The cap (20% of the Item price) is domain policy, not configuration.
 */
@Component
public class DisputeFeeSchedule {

    private final Money arbitrationCost;
    private final Money appealFee;
    private final Duration appealWindow;

    public DisputeFeeSchedule(@Value("${hireai.arbitration.cost:3.00}") BigDecimal arbitrationCost,
                              @Value("${hireai.arbitration.appeal-fee:5.00}") BigDecimal appealFee,
                              @Value("${hireai.arbitration.appeal-window:PT24H}") Duration appealWindow) {
        this.arbitrationCost = Money.of(arbitrationCost);
        this.appealFee = Money.of(appealFee);
        this.appealWindow = appealWindow;
    }

    /** The full (uncapped) arbitration cost. */
    public Money arbitrationCost() { return arbitrationCost; }

    /** The full (uncapped) Appeal Fee. */
    public Money appealFee() { return appealFee; }

    /** How long a proposed ruling waits before it is accepted on everyone's behalf. */
    public Duration appealWindow() { return appealWindow; }

    /** What a loser bears of the arbitration cost on a task of this price. */
    public Money arbitrationCostFor(Money itemPrice) {
        return SettlementPolicy.disputeFeeCap(arbitrationCost, itemPrice);
    }

    /** What a loser bears of the Appeal Fee on a task of this price. */
    public Money appealFeeFor(Money itemPrice) {
        return SettlementPolicy.disputeFeeCap(appealFee, itemPrice);
    }
}
