package com.hireai.domain.biz.task.event;

import com.hireai.domain.shared.model.Money;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when a task is submitted and its budget frozen. No consumer yet; this is
 * the seam the routing module (Module 3) will subscribe to in order to start dispatch.
 */
public record TaskSubmittedDomainEvent(UUID taskId, UUID clientId, Money budget, Instant occurredAt) {
}
