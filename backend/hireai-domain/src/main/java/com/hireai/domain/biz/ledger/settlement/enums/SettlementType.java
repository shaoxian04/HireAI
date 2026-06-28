package com.hireai.domain.biz.ledger.settlement.enums;

/** How a task settled: full payout (ACCEPT), full refund (REJECT), or partial split (SPLIT, Module 4). */
public enum SettlementType {
    ACCEPT,
    REJECT,
    SPLIT
}
