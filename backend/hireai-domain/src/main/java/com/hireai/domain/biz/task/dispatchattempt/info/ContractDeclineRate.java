package com.hireai.domain.biz.task.dispatchattempt.info;

import com.hireai.domain.biz.task.dispatchattempt.enums.DispatchOutcome;

import java.util.Map;

/**
 * An Agent's public contract-decline rate (#93, D2): of the WorkOrders whose Contract it answered
 * (took it, or refused it with a ContractDecline), how many it refused. Counted over every version of
 * the Agent, all time. Busy, unreachable and unavailable attempts say nothing about the Contract and
 * are left out. It is shown to Clients only: it is not a reputation event and not a ranking input.
 *
 * @param declined attempts that ended {@code CONTRACT_DECLINED}
 * @param answered attempts that ended {@code ACCEPTED} or {@code CONTRACT_DECLINED}
 */
public record ContractDeclineRate(long declined, long answered) {

    /** An Agent that has answered no WorkOrder yet. */
    public static final ContractDeclineRate NONE = new ContractDeclineRate(0, 0);

    public ContractDeclineRate {
        if (declined < 0 || answered < declined) {
            throw new IllegalArgumentException("Need 0 <= declined <= answered; got " + declined + "/" + answered);
        }
    }

    public static ContractDeclineRate fromOutcomeCounts(Map<DispatchOutcome, Long> counts) {
        long declined = counts.getOrDefault(DispatchOutcome.CONTRACT_DECLINED, 0L);
        long answered = counts.entrySet().stream()
                .filter(e -> e.getKey().answeredTheContract())
                .mapToLong(Map.Entry::getValue)
                .sum();
        return new ContractDeclineRate(declined, answered);
    }

    /** {@code declined / answered}, or null when the Agent has answered nothing (no evidence is not 0%). */
    public Double rate() {
        return answered == 0 ? null : (double) declined / answered;
    }
}
