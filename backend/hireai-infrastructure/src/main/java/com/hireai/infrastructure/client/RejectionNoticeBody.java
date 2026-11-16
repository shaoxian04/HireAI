package com.hireai.infrastructure.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * The rejection notice (#95): the JSON body POSTed to an Agent's webhook when a Client rejects its
 * result for reason A/B/C, or a buyer's reviewer rejects one Item ({@code task.item_rejected}, with the
 * Item fields set). {@code event} is {@code task.rejected}, which a WorkOrder never carries,
 * so one webhook can tell the two apart. The Builder-facing contract lives in
 * {@code docs/details/matching.md}.
 */
public record RejectionNoticeBody(
        String event,
        UUID taskId,
        UUID agentVersionId,
        int round,
        String reasonCategory,
        String reason,
        String responseDeadline,
        boolean revisionAllowed,
        String responseUrl,
        String title,
        String description,
        JsonNode outputSpec,
        /** Set on a single Item's rejection ({@code task.item_rejected}, spec D17′): which Item. */
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer itemNo,
        @JsonInclude(JsonInclude.Include.NON_NULL) String itemInput,
        /** The Item's result being rejected, as the Agent delivered it. */
        @JsonInclude(JsonInclude.Include.NON_NULL) String itemResult) {
}
