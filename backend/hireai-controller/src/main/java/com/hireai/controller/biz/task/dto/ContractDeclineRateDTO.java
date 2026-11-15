package com.hireai.controller.biz.task.dto;

import com.hireai.domain.biz.task.dispatchattempt.info.ContractDeclineRate;

/**
 * An Agent's public contract-decline rate (#93): {@code declined} of the {@code answered} WorkOrders
 * whose Contract it took or refused. {@code rate} is {@code declined / answered}, null when it has
 * answered none. Shown on the shortlist and the agent page.
 */
public record ContractDeclineRateDTO(long declined, long answered, Double rate) {

    public static ContractDeclineRateDTO from(ContractDeclineRate rate) {
        ContractDeclineRate r = rate == null ? ContractDeclineRate.NONE : rate;
        return new ContractDeclineRateDTO(r.declined(), r.answered(), r.rate());
    }
}
