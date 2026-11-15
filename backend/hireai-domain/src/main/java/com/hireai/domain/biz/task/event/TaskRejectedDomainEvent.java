package com.hireai.domain.biz.task.event;

import java.util.UUID;

/**
 * Published when a Client's A/B/C rejection opens a round of the rejection response path (#95).
 * After the rejection commits, a listener sends the Agent its signed rejection notice; the
 * rejection-window sweeper retries a notice that did not get through.
 */
public record TaskRejectedDomainEvent(UUID taskId) {
}
