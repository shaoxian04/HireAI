package com.hireai.domain.biz.acceptance.extraction.model;

import com.hireai.domain.biz.acceptance.extraction.enums.ExtractionStatus;
import com.hireai.domain.biz.acceptance.template.model.TemplateRef;

import java.util.List;

/**
 * What a mode-1 Task keeps of its extraction (spec S6): which Template version the requirements were
 * mapped against, whether the extractor answered, and each requirement, mapped or not. The unmapped
 * ones are the requirements no automatic check covers (for AI Review, the Client's review and
 * arbitration evidence). The Contract is not repeated: the Task's frozen Contract is the binding one.
 */
public record ExtractionRecord(TemplateRef template, ExtractionStatus status, List<ExtractedRequirement> requirements) {

    public ExtractionRecord {
        if (template == null || status == null) {
            throw new IllegalArgumentException("An extraction record needs a Template and a status");
        }
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
    }
}
