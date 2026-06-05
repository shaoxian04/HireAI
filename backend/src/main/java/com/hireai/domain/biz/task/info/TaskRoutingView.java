package com.hireai.domain.biz.task.info;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Read-model projection of a Task carrying only what routing needs to match it
 * (category, budget, current status). Framework-free. Produced by
 * {@code TaskReadAppService.getRoutingView} (Plan 3) and passed to the routing
 * matcher (Plan 4); the routing trigger re-reads the task rather than enriching the
 * submitted event, so the event contract stays stable.
 */
public record TaskRoutingView(UUID taskId, String category, BigDecimal budget, String status) {
}
