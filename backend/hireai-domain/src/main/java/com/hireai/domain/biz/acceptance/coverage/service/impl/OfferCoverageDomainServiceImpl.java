package com.hireai.domain.biz.acceptance.coverage.service.impl;

import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.contract.model.ParameterValue;
import com.hireai.domain.biz.acceptance.coverage.info.Coverage;
import com.hireai.domain.biz.acceptance.coverage.service.OfferCoverageDomainService;
import com.hireai.domain.biz.acceptance.offer.model.Offer;
import com.hireai.domain.biz.acceptance.offer.model.OfferTerm;
import com.hireai.domain.biz.acceptance.offer.model.ParameterRange;
import com.hireai.domain.biz.acceptance.template.enums.ParameterComparison;
import com.hireai.domain.biz.acceptance.template.model.TemplateModel;
import com.hireai.domain.biz.acceptance.template.model.TemplateParameter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class OfferCoverageDomainServiceImpl implements OfferCoverageDomainService {

    @Override
    public Coverage coverage(TemplateModel template, Contract contract, Offer offer) {
        if (offer == null) {
            return Coverage.templateNotOffered();
        }
        Optional<OfferTerm> term = offer.terms().stream()
                .filter(t -> t.template().equals(contract.template()))
                .findFirst();
        if (term.isEmpty()) {
            return Coverage.templateNotOffered();
        }
        List<String> blocking = new ArrayList<>();
        // Contract params are held sorted by name, so the blockers come out in name order.
        for (Map.Entry<String, ParameterValue> entry : contract.params().entrySet()) {
            ParameterComparison comparison = comparisonOf(template, entry.getKey());
            ParameterRange range = term.get().params().get(entry.getKey());
            if (!passes(comparison, entry.getValue(), range)) {
                blocking.add(entry.getKey());
            }
        }
        return Coverage.blockedBy(blocking);
    }

    /** Null when the Template declares no such parameter: then nothing can cover the value. */
    private static ParameterComparison comparisonOf(TemplateModel template, String name) {
        return template.parameters().stream()
                .filter(p -> p.name().equals(name))
                .map(TemplateParameter::comparison)
                .findFirst()
                .orElse(null);
    }

    private static boolean passes(ParameterComparison comparison, ParameterValue value, ParameterRange range) {
        if (value instanceof ParameterValue.Flag flag && !flag.value()) {
            return true; // a false flag asks nothing of the Agent
        }
        if (comparison == null || range == null) {
            return false;
        }
        return switch (comparison) {
            case MAX -> value instanceof ParameterValue.Number n && range instanceof ParameterRange.Bound b
                    && n.value().compareTo(b.value()) <= 0;
            case MIN -> value instanceof ParameterValue.Number n && range instanceof ParameterRange.Bound b
                    && n.value().compareTo(b.value()) >= 0;
            case SUBSET -> value instanceof ParameterValue.TextSet set
                    && (range instanceof ParameterRange.AnyValue
                        || range instanceof ParameterRange.OneOf oneOf && oneOf.values().containsAll(set.values()));
            case ENUM -> value instanceof ParameterValue.Text text
                    && (range instanceof ParameterRange.AnyValue
                        || range instanceof ParameterRange.OneOf oneOf && oneOf.values().contains(text.value()));
            case FLAG -> value instanceof ParameterValue.Flag && range instanceof ParameterRange.Flag f
                    && f.acceptsTrue();
        };
    }
}
