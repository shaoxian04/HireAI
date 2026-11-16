package com.hireai.infrastructure.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.hireai.domain.biz.task.routing.info.ReworkRequest;

import java.util.List;

import java.util.UUID;

/**
 * The WorkOrder: the JSON body POSTed to an Agent's webhook. Exactly these fields and nothing
 * else (no client identity, no budget). {@code contract} is the task's frozen Contract, the binding
 * terms the result is checked against (Hard Invariant #4); {@code outputSpec} describes it (format,
 * schema, prose criteria) for Agents that read only that. Both are embedded as raw JSON (not
 * re-quoted strings), so they are typed as {@link JsonNode}; {@code executionDeadline} is an
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
        /** A multi-Item Task's Items (spec S3); left out entirely for a single-deliverable Task. */
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Item> items,
        /** Only on a rework WorkOrder (spec D17): the checks the last result failed. */
        @JsonInclude(JsonInclude.Include.NON_NULL) ReworkRequest rework,
        /** The frozen Contract (#110); left out only for a message queued before it was sent. */
        @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode contract) {

    public WebhookDispatchBody(UUID taskId, UUID agentVersionId, String title, String description,
                               String category, JsonNode outputSpec, String executionDeadline, String callbackUrl) {
        this(taskId, agentVersionId, title, description, category, outputSpec, executionDeadline, callbackUrl,
                List.of(), null, null);
    }

    public WebhookDispatchBody(UUID taskId, UUID agentVersionId, String title, String description,
                               String category, JsonNode outputSpec, String executionDeadline, String callbackUrl,
                               List<Item> items, ReworkRequest rework) {
        this(taskId, agentVersionId, title, description, category, outputSpec, executionDeadline, callbackUrl,
                items, rework, null);
    }

    /**
     * One Item: answer it in the callback's {@code items} under the same {@code itemNo}. {@code source} is
     * the Item's input document (Engagements only), left out for a text Item.
     */
    public record Item(int itemNo, String input, @JsonInclude(JsonInclude.Include.NON_NULL) Source source) {

        public Item(int itemNo, String input) {
            this(itemNo, input, null);
        }
    }

    /**
     * An Item's input document (spec S4, D31): GET {@code url} (no other credentials) before
     * {@code expiresAt}, the execution deadline; the bytes' SHA-256 is {@code sha256}. Each link works for
     * this one Item only, and every download is logged for the buyer. The document is confidential: the
     * buyer sent it under an Engagement with this Agent.
     */
    public record Source(String url, String sha256, String mediaType, long sizeBytes, String filename,
                         String expiresAt) {
    }

}
