package com.hireai.domain.biz.acceptance.extraction.model;

import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.extraction.enums.ExtractionStatus;
import com.hireai.domain.biz.acceptance.template.model.TemplateRef;

import java.util.List;

/**
 * What requirement extraction made of a description (spec S6): the Contract it adopts (the base plus
 * every vetted mapping) and each requirement, mapped or not. In mode 1 the Contract is adopted at
 * submit, where the version transition may still move it to another Template version (D36); the
 * Contract frozen on the Task is the binding one. {@code template} is the version the requirements
 * were mapped against.
 */
public record ContractExtraction(TemplateRef template, ExtractionStatus status, Contract contract,
                                 List<ExtractedRequirement> requirements) {

    public ContractExtraction {
        if (template == null || status == null || contract == null) {
            throw new IllegalArgumentException("An extraction needs a Template, a status and a Contract");
        }
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
    }

    /** What a Task keeps of this extraction (everything but the Contract). */
    public ExtractionRecord record() {
        return new ExtractionRecord(template, status, requirements);
    }

    /** The extractor could not answer: the Contract is the base (the Template's defaults). */
    public static ContractExtraction unavailable(Contract base) {
        return new ContractExtraction(base.template(), ExtractionStatus.UNAVAILABLE, base, List.of());
    }
}
