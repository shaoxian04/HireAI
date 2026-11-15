package com.hireai.domain.biz.acceptance.template.model;

import com.hireai.domain.biz.acceptance.contract.model.ParameterValue;
import com.hireai.domain.biz.acceptance.offer.model.ParameterRange;
import com.hireai.domain.biz.acceptance.template.enums.ParameterComparison;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * One parameter of a Template: its name, a plain-words label and description for people, the one
 * {@link ParameterComparison} it follows, and the {@link ParameterBounds} every Contract value and
 * every Offer range must stay inside. Every parameter is optional on a Contract.
 */
public record TemplateParameter(String name, String label, String description,
                                ParameterComparison comparison, ParameterBounds bounds) {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{0,63}");

    public TemplateParameter {
        if (name == null || !NAME.matcher(name).matches()) {
            throw invalid("Parameter name must be lowercase letters, digits and underscores: " + name);
        }
        if (label == null || label.isBlank()) {
            throw invalid("Parameter " + name + " needs a label");
        }
        if (comparison == null || bounds == null) {
            throw invalid("Parameter " + name + " needs a comparison and bounds");
        }
        boolean fits = switch (comparison) {
            case MAX, MIN -> bounds.isNumeric();
            case SUBSET -> bounds.isClosedSet();
            case ENUM -> bounds.isClosedSet() || bounds.isOpenText();
            case FLAG -> bounds.isNone();
        };
        if (!fits) {
            throw invalid("Parameter " + name + " has bounds that do not fit its comparison "
                    + comparison.wireName());
        }
    }

    /** A Contract's value for this parameter must be of the comparison's kind and inside the bounds. */
    public void requireValid(ParameterValue value) {
        boolean ok = switch (comparison) {
            case MAX, MIN -> value instanceof ParameterValue.Number n && bounds.inNumericRange(n.value());
            case SUBSET -> value instanceof ParameterValue.TextSet s && allowed(s.values());
            case ENUM -> value instanceof ParameterValue.Text t && allowed(t.value());
            case FLAG -> value instanceof ParameterValue.Flag;
        };
        if (!ok) {
            throw invalid("Parameter " + name + " is outside the Template's bounds (" + comparison.wireName() + ")");
        }
    }

    /** An Offer's range for this parameter must be of the comparison's kind and inside the bounds. */
    public void requireValid(ParameterRange range) {
        boolean ok = switch (comparison) {
            case MAX, MIN -> range instanceof ParameterRange.Bound b && bounds.inNumericRange(b.value());
            case SUBSET, ENUM -> range instanceof ParameterRange.AnyValue
                    || range instanceof ParameterRange.OneOf o && o.values().stream().allMatch(this::allowed);
            case FLAG -> range instanceof ParameterRange.Flag;
        };
        if (!ok) {
            throw invalid("Offer range for parameter " + name + " is outside the Template's bounds ("
                    + comparison.wireName() + ")");
        }
    }

    private boolean allowed(Set<String> values) {
        return values.stream().allMatch(this::allowed);
    }

    private boolean allowed(String value) {
        if (bounds.allowedValues() != null) {
            return bounds.allowedValues().contains(value);
        }
        return value.length() <= bounds.maxLength();
    }

    private static DomainException invalid(String message) {
        return new DomainException(ResultCode.VALIDATION_ERROR, message);
    }
}
