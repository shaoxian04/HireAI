package com.hireai.domain.biz.task.routing.info;

import java.util.UUID;

/**
 * The dispatch envelope published to RabbitMQ (serialised as a JSON payload via Jackson
 * by the messaging adapter, Plan 2). Framework-free. Carries the routing identity
 * ({@code taskId}, {@code agentVersionId}), the target {@code webhookUrl}, a tracing
 * {@code correlationId}, and the {@link TaskDispatchPayload} the agent executes.
 */
public record DispatchMessage(UUID taskId, UUID agentVersionId, String webhookUrl,
                              String correlationId, TaskDispatchPayload payload) {
}
