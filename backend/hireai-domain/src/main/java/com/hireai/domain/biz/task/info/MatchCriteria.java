package com.hireai.domain.biz.task.info;

import com.hireai.domain.biz.acceptance.coverage.info.ContractTerms;

import java.math.BigDecimal;

/**
 * Minimal input the routing matcher needs: a task category and a budget ceiling. Extracted so the
 * matcher no longer requires a full {@link TaskRoutingView} — the frontend match-preview has no task.
 * {@link TaskRoutingView} implements this; {@link PreviewCriteria} is the task-less carrier.
 *
 * <p>{@link #contractTerms()} is the Contract every eligible Agent's Offer must cover (spec D34), or
 * null for no coverage filter (a match-preview that states no Contract).
 */
public interface MatchCriteria {
    String category();

    BigDecimal budget();

    default ContractTerms contractTerms() {
        return null;
    }

    /** These criteria, also requiring Offer coverage of {@code terms} (null = none). */
    default MatchCriteria withTerms(ContractTerms terms) {
        return new WithTerms(category(), budget(), terms);
    }

    record WithTerms(String category, BigDecimal budget, ContractTerms contractTerms) implements MatchCriteria {
    }
}
