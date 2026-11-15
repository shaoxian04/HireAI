package com.hireai.domain.biz.acceptance.contract.model;

import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * The value a Contract gives one Template parameter. The kind follows the parameter's comparison:
 * {@link Number} for {@code max}/{@code min}, {@link TextSet} for {@code subset}, {@link Text} for
 * {@code enum}, {@link Flag} for {@code flag}.
 */
public sealed interface ParameterValue {

    record Number(BigDecimal value) implements ParameterValue {
        public Number {
            if (value == null) {
                throw new DomainException(ResultCode.VALIDATION_ERROR, "A number parameter needs a value");
            }
        }
    }

    record Text(String value) implements ParameterValue {
        public Text {
            if (value == null) {
                throw new DomainException(ResultCode.VALIDATION_ERROR, "A text parameter needs a value");
            }
        }
    }

    /** A set of strings, held sorted so equal sets serialise identically. */
    record TextSet(Set<String> values) implements ParameterValue {
        public TextSet {
            if (values == null || values.stream().anyMatch(v -> v == null)) {
                throw new DomainException(ResultCode.VALIDATION_ERROR, "A set parameter needs non-null values");
            }
            values = Collections.unmodifiableSortedSet(new TreeSet<>(values));
        }
    }

    record Flag(boolean value) implements ParameterValue {
    }
}
