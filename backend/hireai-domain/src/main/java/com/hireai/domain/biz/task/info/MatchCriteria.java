package com.hireai.domain.biz.task.info;

import java.math.BigDecimal;

/**
 * Minimal input the routing matcher needs: a task category and a budget ceiling. Extracted so the
 * matcher no longer requires a full {@link TaskRoutingView} — the frontend match-preview has no task.
 * {@link TaskRoutingView} implements this; {@link PreviewCriteria} is the task-less carrier.
 */
public interface MatchCriteria {
    String category();

    BigDecimal budget();
}
