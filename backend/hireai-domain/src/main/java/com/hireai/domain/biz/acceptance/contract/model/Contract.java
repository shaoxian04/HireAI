package com.hireai.domain.biz.acceptance.contract.model;

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
 * <p>Later slices add the AI Review settings and their frozen fee (spec S10) here.
 */
public record Contract(TemplateRef template, Map<String, ParameterValue> params) {

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
}
