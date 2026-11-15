package com.hireai.application.biz.acceptance.impl;

import com.hireai.application.biz.acceptance.TemplateReadAppService;
import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.offer.model.Offer;
import com.hireai.domain.biz.acceptance.offer.model.OfferTerm;
import com.hireai.domain.biz.acceptance.template.model.TemplateModel;
import com.hireai.domain.biz.acceptance.template.model.TemplateRef;
import com.hireai.domain.biz.acceptance.template.repository.TemplateRepository;
import com.hireai.domain.biz.adjudication.service.ValidationDomainService;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Loads Templates and delegates the bounds rules to the {@link TemplateModel} aggregate, then asks
 * the {@link ValidationDomainService} whether the terms could actually be checked (spec D8): a
 * schema that is not a valid JSON Schema is refused here rather than silently skipped later.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TemplateReadAppServiceImpl implements TemplateReadAppService {

    private final TemplateRepository templateRepository;
    private final ValidationDomainService validationDomainService;

    @Override
    public List<TemplateModel> list() {
        return templateRepository.findAll();
    }

    @Override
    public TemplateModel get(TemplateRef ref) {
        return templateRepository.find(ref)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Template not found: " + ref));
    }

    @Override
    public void requireValid(Contract contract) {
        known(contract.template()).requireValid(contract);
        validationDomainService.requireCheckable(contract);
    }

    @Override
    public void requireValid(Offer offer) {
        for (OfferTerm term : offer.terms()) {
            known(term.template()).requireValid(term);
        }
        validationDomainService.requireCheckable(offer);
    }

    private TemplateModel known(TemplateRef ref) {
        return templateRepository.find(ref)
                .orElseThrow(() -> new DomainException(ResultCode.VALIDATION_ERROR, "Unknown Template " + ref));
    }
}
