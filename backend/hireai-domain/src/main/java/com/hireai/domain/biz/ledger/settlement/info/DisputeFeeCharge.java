package com.hireai.domain.biz.ledger.settlement.info;

import com.hireai.domain.biz.ledger.settlement.enums.DisputeFeeType;
import com.hireai.domain.shared.model.Money;

/**
 * How one dispute fee was borne: its full cost, the cap applied (20% of the Item price), what each
 * side was actually charged, and what the platform covered (the excess over the cap, plus anything a
 * loser's balance could not pay). {@code fullCost = clientCharged + builderCharged + platformCovered}.
 */
public record DisputeFeeCharge(DisputeFeeType type, Money fullCost, Money cappedAmount,
                               Money clientCharged, Money builderCharged, Money platformCovered) {
}
