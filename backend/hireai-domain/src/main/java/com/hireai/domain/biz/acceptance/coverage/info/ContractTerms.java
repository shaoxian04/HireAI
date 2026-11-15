package com.hireai.domain.biz.acceptance.coverage.info;

import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.template.model.TemplateModel;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

/**
 * A Contract together with the Template version it names: what an Offer must cover. The Template
 * supplies each parameter's comparison, which the Contract's values alone don't carry.
 */
public record ContractTerms(TemplateModel template, Contract contract) {

    public ContractTerms {
        if (template == null || contract == null || !template.ref().equals(contract.template())) {
            throw new DomainException(ResultCode.INTERNAL_ERROR,
                    "Contract terms need the Contract's own Template version");
        }
    }
}
