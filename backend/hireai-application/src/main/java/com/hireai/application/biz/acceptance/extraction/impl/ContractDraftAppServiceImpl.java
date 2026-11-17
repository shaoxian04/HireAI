package com.hireai.application.biz.acceptance.extraction.impl;

import com.hireai.application.biz.acceptance.TemplateReadAppService;
import com.hireai.application.biz.acceptance.extraction.ContractDraftAppService;
import com.hireai.application.port.extraction.ExtractionUnavailableException;
import com.hireai.application.port.extraction.RequirementExtractorPort;
import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.extraction.model.ContractExtraction;
import com.hireai.domain.biz.acceptance.extraction.model.ProposedRequirement;
import com.hireai.domain.biz.acceptance.extraction.service.RequirementExtractionDomainService;
import com.hireai.domain.biz.acceptance.template.model.TemplateModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Thin orchestration: load the Template, ask the extractor (outside any transaction), and let the
 * domain service vet every proposal against the full submit check of a Contract. Any extractor failure
 * falls back to the base Contract (spec S6: "when the LLM is down, the Contract is the Template's
 * defaults").
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ContractDraftAppServiceImpl implements ContractDraftAppService {

    private final TemplateReadAppService templateReadAppService;
    private final RequirementExtractorPort requirementExtractorPort;
    private final RequirementExtractionDomainService requirementExtractionDomainService;
    private final com.hireai.application.biz.acceptance.extraction.ExtractionRateLimiter rateLimiter;

    @Override
    public ContractExtraction draft(java.util.UUID requestedBy, String title, String description,
                                    String acceptanceCriteria, Contract base) {
        templateReadAppService.requireValid(base);
        rateLimiter.acquire(requestedBy); // before the LLM call, and before a submit freezes anything
        TemplateModel template = templateReadAppService.get(base.template());
        List<ProposedRequirement> proposals;
        try {
            proposals = requirementExtractorPort.extract(template, taskText(title, description, acceptanceCriteria));
        } catch (ExtractionUnavailableException e) {
            log.info("Requirement extraction unavailable for {}; using the Template's defaults: {}",
                    template.ref(), e.getMessage());
            return ContractExtraction.unavailable(base);
        } catch (RuntimeException e) {
            log.warn("Requirement extraction failed for {}; using the Template's defaults", template.ref(), e);
            return ContractExtraction.unavailable(base);
        }
        return requirementExtractionDomainService.adopt(template, base, proposals, templateReadAppService::requireValid);
    }

    private static String taskText(String title, String description, String acceptanceCriteria) {
        StringBuilder text = new StringBuilder();
        if (title != null && !title.isBlank()) {
            text.append("Title: ").append(title.strip()).append("\n\n");
        }
        if (description != null && !description.isBlank()) {
            text.append(description.strip()).append("\n\n");
        }
        if (acceptanceCriteria != null && !acceptanceCriteria.isBlank()) {
            text.append("Acceptance criteria: ").append(acceptanceCriteria.strip());
        }
        return text.toString().strip();
    }
}
