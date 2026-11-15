package com.hireai.infrastructure.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.hireai.domain.biz.task.routing.info.ReworkRequest;

import java.util.UUID;

/**
 * The WorkOrder: the JSON body POSTed to an Agent's webhook. Exactly these fields and nothing
 * else (no client identity, no budget). {@code outputSpec} is embedded as raw JSON (not a
 * re-quoted string), so it is typed as {@link JsonNode}; {@code executionDeadline} is an
 * ISO-8601 instant string. {@code rework} appears only on a rework WorkOrder (spec D17): the
 * rework number, the most allowed and the checks the previous result failed. The Builder-facing
 * contract lives in {@code docs/details/matching.md}.
 */
public record WebhookDispatchBody(
        UUID taskId,
        UUID agentVersionId,
        String title,
        String description,
        String category,
        JsonNode outputSpec,
        String executionDeadline,
        String callbackUrl,
        @JsonInclude(JsonInclude.Include.NON_NULL) ReworkRequest rework) {
}
