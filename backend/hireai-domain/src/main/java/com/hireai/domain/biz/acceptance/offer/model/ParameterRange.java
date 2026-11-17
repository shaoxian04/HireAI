package com.hireai.domain.biz.acceptance.offer.model;

import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * The range of one Template parameter an Offer accepts being held to. The kind follows the
 * parameter's comparison (see {@code ParameterComparison}): {@link Bound} for {@code max}/{@code min},
 * {@link OneOf} or {@link AnyValue} for {@code subset}/{@code enum}, {@link Flag} for {@code flag}.
 *
 * <p>A parameter an Offer term leaves out is accepted only when the Contract leaves it unset too:
 * an Agent is never held to a parameter it did not name.
 */
public sealed interface ParameterRange {

    /** For {@code max}: the largest Contract value accepted. For {@code min}: the smallest. */
    record Bound(BigDecimal value) implements ParameterRange {
        public Bound {
            if (value == null) {
                throw new DomainException(ResultCode.VALIDATION_ERROR, "A bound needs a value");
            }
        }
    }

    /** The accepted values, held sorted so equal ranges serialise identically. */
    record OneOf(Set<String> values) implements ParameterRange {
        public OneOf {
            if (values == null || values.stream().anyMatch(v -> v == null)) {
                throw new DomainException(ResultCode.VALIDATION_ERROR, "A value range needs non-null values");
            }
            values = Collections.unmodifiableSortedSet(new TreeSet<>(values));
        }
    }

    /** Every value the Template's bounds allow (for {@code subset} and {@code enum} only). */
    record AnyValue() implements ParameterRange {
    }

    /** Whether the Agent accepts the flag being set; an unset or false flag is always accepted. */
    record Flag(boolean acceptsTrue) implements ParameterRange {
    }
}
