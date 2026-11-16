package com.hireai.domain.biz.acceptance.extraction.model;

import java.util.Map;

/**
 * One requirement as the extractor proposed it: untrusted (spec S6). {@code check} is the Check
 * Library entry it maps onto, or null for "unmapped"; {@code params} are raw JSON values (a number,
 * a string, a list of strings or a boolean), not yet checked against anything.
 */
public record ProposedRequirement(String text, String check, Map<String, Object> params) {

    public ProposedRequirement {
        params = params == null ? Map.of() : params;
    }
}
