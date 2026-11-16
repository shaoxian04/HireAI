package com.hireai.domain.biz.acceptance.extraction.model;

import com.hireai.domain.biz.acceptance.contract.model.ParameterValue;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * One requirement after deterministic vetting (spec S6, D11): either mapped onto a Check Library
 * entry of the Template with the parameter values it set on the Contract, or unmapped, with why.
 * An unmapped requirement is not checked automatically; it stays for AI Review, the Client's review
 * or arbitration evidence.
 */
public record ExtractedRequirement(String text, String check, Map<String, ParameterValue> params,
                                   String unmappedReason) {

    public ExtractedRequirement {
        params = params == null ? Map.of() : Collections.unmodifiableSortedMap(new TreeMap<>(params));
    }

    public static ExtractedRequirement mapped(String text, String check, Map<String, ParameterValue> params) {
        return new ExtractedRequirement(text, check, params, null);
    }

    public static ExtractedRequirement unmapped(String text, String reason) {
        return new ExtractedRequirement(text, null, Map.of(), reason);
    }

    public boolean mapped() {
        return check != null;
    }
}
