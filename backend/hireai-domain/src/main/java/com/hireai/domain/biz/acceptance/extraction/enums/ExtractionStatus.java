package com.hireai.domain.biz.acceptance.extraction.enums;

/** Whether requirement extraction ran (spec S6). */
public enum ExtractionStatus {
    /** The extractor answered; its vetted mappings shaped the Contract. */
    EXTRACTED,
    /**
     * The extractor could not answer (not configured, down, slow or an unusable reply): the Contract
     * is the Template's defaults and every requirement stays in the description (D12).
     */
    UNAVAILABLE
}
