package com.hireai.domain.biz.acceptance.offer.model;

import com.hireai.domain.biz.acceptance.template.model.TemplateRef;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * One Template version an Offer works under, with the range of each parameter the Agent accepts.
 * A parameter left out is accepted only when a Contract leaves it unset (see {@link ParameterRange}).
 * Bounds are checked by {@code TemplateModel#requireValid(OfferTerm)}.
 */
public record OfferTerm(TemplateRef template, Map<String, ParameterRange> params) {

    public OfferTerm {
        if (template == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "An Offer term needs a Template");
        }
        if (params == null || params.keySet().stream().anyMatch(k -> k == null || k.isBlank())
                || params.values().stream().anyMatch(v -> v == null)) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Offer ranges must be named and set");
        }
        params = Collections.unmodifiableSortedMap(new TreeMap<>(params));
    }
}
