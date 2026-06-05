package com.hireai.controller.biz.agentcallback.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Inbound HTTP DTO for an agent result callback. {@code agentStatus} is COMPLETED or FAILED;
 * {@code resultPayloadJson} is the (string-encoded) result body; {@code resultUrl} and
 * {@code message} are optional.
 */
public record AgentResultCallbackRequest(
        @NotBlank String agentStatus,
        @NotBlank String resultPayloadJson,
        @Size(max = 2000) String resultUrl,
        @Size(max = 2000) String message
) {
}
