package com.hireai.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * JSON body POSTed to an Agent's webhook (CONTRACTS wire contract B). {@code expectedDeliverable}
 * and {@code outputSpec} are embedded as raw JSON (not re-quoted strings), so they are typed as
 * {@link JsonNode}; {@link AgentDispatchClient} parses them from the {@code DispatchMessage} payload's
 * JSON strings before sending.
 */
public record WebhookDispatchBody(
        UUID taskId,
        String category,
        String title,
        String description,
        JsonNode expectedDeliverable,
        JsonNode outputSpec,
        String callbackUrl) {
}
