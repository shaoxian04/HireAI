package com.hireai.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * The WorkOrder: the JSON body POSTed to an Agent's webhook. Exactly these fields and nothing
 * else (no client identity, no budget). {@code outputSpec} is embedded as raw JSON (not a
 * re-quoted string), so it is typed as {@link JsonNode}; {@code executionDeadline} is an
 * ISO-8601 instant string. The Builder-facing contract lives in {@code docs/details/matching.md}.
 */
public record WebhookDispatchBody(
        UUID taskId,
        UUID agentVersionId,
        String title,
        String description,
        String category,
        JsonNode outputSpec,
        String executionDeadline,
        String callbackUrl) {
}
