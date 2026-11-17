package com.hireai.domain.biz.acceptance.contract.model;

import com.hireai.domain.biz.acceptance.aireview.model.AiReviewTerms;
import com.hireai.domain.biz.acceptance.template.model.TemplateRef;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * The single binding agreement a Task is judged against: one Template version plus the Task's
 * parameter values, frozen at submit (ADR 0009). Immutable; parameters are held sorted by name so
 * equal Contracts serialise identically. Conformance to the Template's bounds is checked by
 * {@code TemplateModel#requireValid(Contract)}; this record checks only its own shape.
 *
 * <p>{@code aiReview} is the optional AI Review the Client chose at submit, with its frozen fee
 * (spec S10); null when there is none. It never affects Offer coverage of the Template terms: the
 * Offer's own AI Review flag is checked when the booking adds it.
 */
public record Contract(TemplateRef template, Map<String, ParameterValue> params, AiReviewTerms aiReview) {

    public Contract {
        if (template == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "A Contract needs a Template");
        }
        if (params == null || params.keySet().stream().anyMatch(k -> k == null || k.isBlank())
                || params.values().stream().anyMatch(v -> v == null)) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Contract parameters must be named and set");
        }
        params = Collections.unmodifiableSortedMap(new TreeMap<>(params));
    }

    public Contract(TemplateRef template, Map<String, ParameterValue> params) {
        this(template, params, null);
    }

    /** This Contract with {@code terms} as its AI Review. */
    public Contract withAiReview(AiReviewTerms terms) {
        return new Contract(template, params, terms);
    }
}
