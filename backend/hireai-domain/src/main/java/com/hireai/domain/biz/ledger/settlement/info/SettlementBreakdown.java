package com.hireai.domain.biz.ledger.settlement.info;

import com.hireai.domain.shared.model.Money;

/** Result carrier for an acceptance settlement: what the builder got and what the platform kept. */
public record SettlementBreakdown(Money net, Money commission) {
}
