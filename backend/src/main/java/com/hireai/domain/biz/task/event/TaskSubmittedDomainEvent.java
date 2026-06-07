package com.hireai.domain.biz.task.event;

import com.hireai.domain.shared.model.Money;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when a task is submitted and its budget frozen. The routing module subscribes
 * to this event after commit (RoutingEventListener). When {@code directAgentVersionId} is
 * non-null the task was submitted via direct booking; routing SKIPS the matcher and
 * dispatches directly to that version. Null means normal competitive routing.
 */
public record TaskSubmittedDomainEvent(UUID taskId, UUID clientId, Money budget, Instant occurredAt,
                                       @Nullable UUID directAgentVersionId) {
}
