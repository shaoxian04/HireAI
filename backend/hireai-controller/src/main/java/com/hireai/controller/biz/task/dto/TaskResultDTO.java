package com.hireai.controller.biz.task.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbound HTTP DTO for the single result an Agent posted back for a task. No domain types leak
 * across the boundary. {@code resultPayloadJson} is the raw JSONB payload as a string (the UI
 * pretty-prints it); {@code resultUrl} is nullable.
 *
 * <p>{@code deliverable} is the frozen DeliverySnapshot of a file result (D24), omitted when there is
 * none. It is what the Client reviews and downloads ({@code GET /api/tasks/{id}/deliverable});
 * {@code resultUrl} only records where it was fetched from and is never followed again.
 */
public record TaskResultDTO(
        UUID taskId,
        String agentStatus,
        String resultPayloadJson,
        String resultUrl,
        Instant receivedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) DeliverableDTO deliverable
) {

    /** The snapshot's metadata; the bytes are served by the owner-only download endpoint. */
    public record DeliverableDTO(String mediaType, String declaredMediaType, long sizeBytes, String sha256,
                                 Instant fetchedAt) {
    }
}
