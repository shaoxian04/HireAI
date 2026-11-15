package com.hireai.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * The rejection notice (#95): the JSON body POSTed to an Agent's webhook when a Client rejects its
 * result for reason A/B/C. {@code event} is {@code task.rejected}, which a WorkOrder never carries,
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
        JsonNode outputSpec) {
}
