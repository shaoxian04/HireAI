package com.hireai.controller.biz.acceptance.converter;

import com.hireai.controller.biz.acceptance.dto.ContractRequest;
import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.contract.model.ParameterValue;
import com.hireai.domain.biz.acceptance.template.model.TemplateRef;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Reads an inbound {@link ContractRequest} into a {@link Contract}. Only the JSON kind of each value is
 * read here; whether it fits the parameter's comparison and bounds is the Template's check.
 */
public final class ContractRequest2ModelConverter {

    private static final int MAX_SET_SIZE = 64;

    private ContractRequest2ModelConverter() {
    }

    /** Null when the request states no Contract. */
    public static Contract toModel(ContractRequest request) {
        if (request == null) {
            return null;
        }
        Map<String, ParameterValue> params = new LinkedHashMap<>();
        if (request.params() != null) {
            request.params().forEach((name, raw) -> params.put(name, value(name, raw)));
        }
        return new Contract(new TemplateRef(request.template(), request.version()), params);
    }

    private static ParameterValue value(String name, Object raw) {
        if (raw instanceof Boolean b) {
            return new ParameterValue.Flag(b);
        }
        if (raw instanceof Number n) {
            return new ParameterValue.Number(new BigDecimal(n.toString()));
        }
        if (raw instanceof String s) {
            return new ParameterValue.Text(s);
        }
        if (raw instanceof Collection<?> values && values.size() <= MAX_SET_SIZE
                && values.stream().allMatch(v -> v instanceof String)) {
            Set<String> set = new LinkedHashSet<>();
            values.forEach(v -> set.add((String) v));
            return new ParameterValue.TextSet(set);
        }
        throw new DomainException(ResultCode.VALIDATION_ERROR,
                "contract.params." + name + " must be a number, a string, an array of strings or a boolean");
    }
}
