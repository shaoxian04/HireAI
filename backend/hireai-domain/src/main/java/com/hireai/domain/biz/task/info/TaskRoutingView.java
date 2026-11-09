package com.hireai.domain.biz.task.info;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Read-model projection of a Task carrying what routing needs to match it (category, budget,
 * current status) and to build the WorkOrder an Agent receives (title, description, and the
 * task's adopted output spec). Framework-free. Produced by {@code TaskReadAppService.getRoutingView}
 * and passed to the routing matcher; the routing trigger re-reads the task rather than enriching
 * the submitted event, so the event contract stays stable.
 *
 * <p>{@code outputSpecJson} is the JSON snapshot of the output_spec column on the task row.
 * It is the binding contract per Hard Invariant #4 — the spec is frozen at submit time and
 * must be used in the dispatch payload, not re-read from the agent version.
 */
public record TaskRoutingView(UUID taskId, String title, String description, String category,
                              BigDecimal budget, String status, String outputSpecJson)
        implements MatchCriteria {
}
