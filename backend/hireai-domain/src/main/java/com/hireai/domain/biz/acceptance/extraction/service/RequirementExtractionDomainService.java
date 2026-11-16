package com.hireai.domain.biz.acceptance.extraction.service;

import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.extraction.model.ContractExtraction;
import com.hireai.domain.biz.acceptance.extraction.model.ProposedRequirement;
import com.hireai.domain.biz.acceptance.template.model.TemplateModel;

import java.util.List;
import java.util.function.Consumer;

/**
 * The deterministic half of requirement extraction (spec S6, D11, D12). The LLM only proposes; this
 * service decides. A proposal shapes the Contract only when it names a check (not a Finding) the
 * Template lists, sets only parameters that check reads, switches the check on, agrees with every
 * value already set, and leaves a Contract the full submit check accepts. Anything else is kept as an
 * unmapped requirement, with the reason. Framework-free.
 */
public interface RequirementExtractionDomainService {

    /** At most this many requirements are kept from one reply; the rest are ignored. */
    int MAX_REQUIREMENTS = 20;
    /** A requirement's text is cut to this many characters. */
    int MAX_TEXT_LENGTH = 300;

    /**
     * Adopts the vetted proposals onto {@code base} (the Template's defaults plus whatever the request
     * already fixed, which is never overridden). {@code requireValid} is the full submit check of a
     * Contract (bounds, version, checkability); it throws a DomainException when the Contract fails.
     */
    ContractExtraction adopt(TemplateModel template, Contract base, List<ProposedRequirement> proposals,
                             Consumer<Contract> requireValid);

    /**
     * A structured Contract that names the checks it relies on (mode 2): each must be a Check Library
     * entry the Template lists, and the Contract must switch it on; VALIDATION_ERROR otherwise.
     */
    void requireNamedChecks(TemplateModel template, Contract contract, List<String> checks);
}
