package com.hireai.application.port.extraction;

import com.hireai.domain.biz.acceptance.extraction.model.ProposedRequirement;
import com.hireai.domain.biz.acceptance.template.model.TemplateModel;

import java.util.List;

/**
 * Requirement extraction's one LLM call (spec S6, D11, D12): given a Template and a task's text, it
 * proposes each requirement's mapping onto one of the Template's Check Library entries, with parameter
 * values, or "unmapped". Implemented in infrastructure over an LLM with structured output constrained
 * to the Template's entries and parameters.
 *
 * <p>The task text is client-controlled and the reply is untrusted: callers must vet every proposal
 * deterministically ({@code RequirementExtractionDomainService}) and treat the provider as optional.
 */
public interface RequirementExtractorPort {

    /**
     * The proposed requirements of {@code taskText} under {@code template}.
     *
     * @throws ExtractionUnavailableException when the provider can't answer (not configured, down,
     *         slow past the timeout, a refusal or an unusable reply)
     */
    List<ProposedRequirement> extract(TemplateModel template, String taskText);
}
