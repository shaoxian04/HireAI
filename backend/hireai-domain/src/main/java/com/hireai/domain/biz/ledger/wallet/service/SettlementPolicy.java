package com.hireai.domain.biz.ledger.wallet.service;

import com.hireai.domain.shared.model.Money;

import java.math.BigDecimal;

/**
 * The platform's settlement constants (SAD: 15% commission, deducted on release).
 * The single place the rate lives — the DTO layer derives display amounts from here too.
 */
public final class SettlementPolicy {

    public static final BigDecimal COMMISSION_RATE = new BigDecimal("0.15");

    private SettlementPolicy() {
    }

    /** Commission on a budget, rounded half-up to 2dp (Money's fixed scale). */
    public static Money commissionOn(Money budget) {
        return budget.percentage(COMMISSION_RATE);
    }

    /** What the builder receives: budget minus commission (reconciles exactly). */
    public static Money netOf(Money budget) {
        return budget.subtract(commissionOn(budget));
    }
}
