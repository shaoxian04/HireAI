package com.hireai.domain.biz.acceptance.aireview.model;

import com.hireai.domain.shared.model.Money;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The AI Review a Contract carries (spec S10, D5, D37): the criteria code cannot check, in the
 * Client's words, and the fee frozen for them at submit. Part of the frozen Contract, so neither
 * changes after booking and AI Review is never switched on mid-task.
 */
public record AiReviewTerms(List<String> criteria, Money fee) {

    /** The longest criterion the reviewer is asked to judge. */
    public static final int MAX_CRITERION_LENGTH = 300;

    public AiReviewTerms {
        if (criteria == null || criteria.isEmpty()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "AI Review needs at least one criterion");
        }
        Set<String> seen = new HashSet<>();
        List<String> trimmed = criteria.stream().map(c -> c == null ? "" : c.trim()).toList();
        for (String criterion : trimmed) {
            if (criterion.isEmpty() || criterion.length() > MAX_CRITERION_LENGTH) {
                throw new DomainException(ResultCode.VALIDATION_ERROR,
                        "Each AI Review criterion must be 1-" + MAX_CRITERION_LENGTH + " characters");
            }
            if (!seen.add(criterion)) {
                throw new DomainException(ResultCode.VALIDATION_ERROR,
                        "AI Review criterion \"" + criterion + "\" is listed twice");
            }
        }
        if (fee == null || !fee.isPositive()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "An AI Review fee must be positive");
        }
        criteria = List.copyOf(trimmed);
    }
}
