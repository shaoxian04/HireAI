package com.hireai.controller.biz.task.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbound HTTP DTO for the single result an Agent posted back for a task. No domain types leak
 * across the boundary. {@code resultPayloadJson} is the raw JSONB payload as a string (the UI
 * pretty-prints it); {@code resultUrl} is nullable.
 */
public record TaskResultDTO(
        UUID taskId,
        String agentStatus,
        String resultPayloadJson,
        String resultUrl,
        Instant receivedAt
) {
}
