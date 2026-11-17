package com.hireai.domain.biz.acceptance.template.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * The bounds a Template puts on one parameter. Which fields are set depends on the parameter's
 * comparison, and {@link TemplateParameter} enforces the fit:
 * <ul>
 *   <li>{@code MAX}/{@code MIN}: {@code minimum} and {@code maximum}, inclusive.</li>
 *   <li>{@code SUBSET}: {@code allowedValues}, the universe a set is drawn from.</li>
 *   <li>{@code ENUM}: either {@code allowedValues} (closed) or {@code maxLength} (open text).</li>
 *   <li>{@code FLAG}: nothing.</li>
 * </ul>
 */
public record ParameterBounds(BigDecimal minimum, BigDecimal maximum, List<String> allowedValues,
                              Integer maxLength) {

    public ParameterBounds {
        allowedValues = allowedValues == null ? null : List.copyOf(allowedValues);
    }

    public static ParameterBounds numeric(BigDecimal minimum, BigDecimal maximum) {
        return new ParameterBounds(minimum, maximum, null, null);
    }

    public static ParameterBounds closedSet(List<String> allowedValues) {
        return new ParameterBounds(null, null, allowedValues, null);
    }

    public static ParameterBounds openText(int maxLength) {
        return new ParameterBounds(null, null, null, maxLength);
    }

    public static ParameterBounds none() {
        return new ParameterBounds(null, null, null, null);
    }

    boolean isNumeric() {
        return minimum != null && maximum != null && minimum.compareTo(maximum) <= 0
                && allowedValues == null && maxLength == null;
    }

    boolean isClosedSet() {
        return allowedValues != null && !allowedValues.isEmpty() && minimum == null && maximum == null
                && maxLength == null;
    }

    boolean isOpenText() {
        return maxLength != null && maxLength > 0 && minimum == null && maximum == null && allowedValues == null;
    }

    boolean isNone() {
        return minimum == null && maximum == null && allowedValues == null && maxLength == null;
    }

    boolean inNumericRange(BigDecimal value) {
        return value.compareTo(minimum) >= 0 && value.compareTo(maximum) <= 0;
    }
}
