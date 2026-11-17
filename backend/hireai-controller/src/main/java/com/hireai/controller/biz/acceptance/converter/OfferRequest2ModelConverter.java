package com.hireai.controller.biz.acceptance.converter;

import com.hireai.controller.biz.acceptance.dto.OfferRequest;
import com.hireai.domain.biz.acceptance.offer.model.Offer;
import com.hireai.domain.biz.acceptance.offer.model.OfferTerm;
import com.hireai.domain.biz.acceptance.offer.model.ParameterRange;
import com.hireai.domain.biz.acceptance.template.model.TemplateRef;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads an inbound {@link OfferRequest} into an {@link Offer}. Only the JSON shape of each range is
 * read here; whether it fits the parameter's comparison and bounds is the Template's check.
 */
public final class OfferRequest2ModelConverter {

    private static final int MAX_VALUES = 64;

    private OfferRequest2ModelConverter() {
    }

    /** Null when the request states no Offer (the current one is kept). */
    public static Offer toModel(OfferRequest request) {
        if (request == null) {
            return null;
        }
        List<OfferTerm> terms = new ArrayList<>();
        for (OfferRequest.TermRequest term : request.terms()) {
            Map<String, ParameterRange> params = new LinkedHashMap<>();
            if (term.params() != null) {
                term.params().forEach((name, raw) -> params.put(name, range(term.template(), name, raw)));
            }
            terms.add(new OfferTerm(new TemplateRef(term.template(), term.version()), params));
        }
        return new Offer(terms, Boolean.TRUE.equals(request.acceptsAiReview()), request.strictestInspectionLevel());
    }

    private static ParameterRange range(String template, String name, Map<String, Object> raw) {
        if (raw != null && raw.size() == 1) {
            Object value = raw.values().iterator().next();
            switch (raw.keySet().iterator().next()) {
                case "bound" -> {
                    if (value instanceof Number n) {
                        return new ParameterRange.Bound(new BigDecimal(n.toString()));
                    }
                }
                case "oneOf" -> {
                    if (value instanceof Collection<?> values && !values.isEmpty() && values.size() <= MAX_VALUES
                            && values.stream().allMatch(v -> v instanceof String)) {
                        Set<String> set = new LinkedHashSet<>();
                        values.forEach(v -> set.add((String) v));
                        return new ParameterRange.OneOf(set);
                    }
                }
                case "any" -> {
                    if (Boolean.TRUE.equals(value)) {
                        return new ParameterRange.AnyValue();
                    }
                }
                case "acceptsTrue" -> {
                    if (value instanceof Boolean b) {
                        return new ParameterRange.Flag(b);
                    }
                }
                default -> {
                }
            }
        }
        throw new DomainException(ResultCode.VALIDATION_ERROR, "The Offer's range for " + template + " " + name
                + " must be one of {\"bound\":number}, {\"oneOf\":[1-" + MAX_VALUES + " strings]}, "
                + "{\"any\":true} or {\"acceptsTrue\":true|false}");
    }
}
