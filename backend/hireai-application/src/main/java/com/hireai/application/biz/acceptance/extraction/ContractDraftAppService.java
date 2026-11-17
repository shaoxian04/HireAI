package com.hireai.application.biz.acceptance.extraction;

import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.extraction.model.ContractExtraction;

import java.util.UUID;

/**
 * Requirement extraction (spec S6): the draft Contract a task's text maps onto. Used by
 * {@code POST /api/contracts/draft} (a preview: it creates no Task and freezes nothing) and by a mode-1
 * submit (description only), which adopts the result. Never runs inside a transaction: the LLM call
 * may take seconds.
 */
public interface ContractDraftAppService {

    /**
     * Extracts {@code title}, {@code description} and {@code acceptanceCriteria} (any may be null) onto
     * {@code base}, the Template's defaults plus whatever the request already fixed (an output spec's
     * schema). VALIDATION_ERROR when {@code base} itself is not a valid new Contract. When the extractor
     * is unavailable the result is {@code base}, with status UNAVAILABLE; it never fails for that.
     * RATE_LIMITED (429) when {@code requestedBy} has made too many extractions in the window
     * ({@link ExtractionRateLimiter}).
     */
    ContractExtraction draft(UUID requestedBy, String title, String description, String acceptanceCriteria,
                             Contract base);
}
