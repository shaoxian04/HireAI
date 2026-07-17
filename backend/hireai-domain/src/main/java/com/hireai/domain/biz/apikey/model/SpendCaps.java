package com.hireai.domain.biz.apikey.model;

import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.math.BigDecimal;

/**
 * The two independent, optional per-key spend caps and their enforcement. A {@code null} cap is
 * uncapped (skipped). {@code checkOrThrow} rejects if EITHER the concurrent frozen-escrow cap or
 * the rolling-24h daily cap would be exceeded by admitting {@code budget}.
 */
public record SpendCaps(BigDecimal spendCap, BigDecimal dailySpendCap) {

    public static SpendCaps of(BigDecimal spendCap, BigDecimal dailySpendCap) {
        return new SpendCaps(spendCap, dailySpendCap);
    }

    public void checkOrThrow(BigDecimal currentCommitted, BigDecimal currentDaily, BigDecimal budget) {
        if (spendCap != null && currentCommitted.add(budget).compareTo(spendCap) > 0) {
            throw new DomainException(ResultCode.SPEND_CAP_EXCEEDED,
                    "Concurrent spend cap exceeded: " + currentCommitted.add(budget)
                            + " would exceed the key's cap of " + spendCap);
        }
        if (dailySpendCap != null && currentDaily.add(budget).compareTo(dailySpendCap) > 0) {
            throw new DomainException(ResultCode.SPEND_CAP_EXCEEDED,
                    "Daily spend cap exceeded: " + currentDaily.add(budget)
                            + " would exceed the key's 24h cap of " + dailySpendCap);
        }
    }
}
