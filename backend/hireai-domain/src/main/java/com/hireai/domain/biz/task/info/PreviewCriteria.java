package com.hireai.domain.biz.task.info;

import java.math.BigDecimal;

/** Task-less {@link MatchCriteria} for the match-preview flow (no task row exists yet). */
public record PreviewCriteria(String category, BigDecimal budget) implements MatchCriteria {
}
