package com.hireai.controller.biz.agentcallback.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Inbound HTTP DTO for an agent result callback. {@code taskId} is the task the result answers;
 * it is covered by the Agent's signature and must equal the callback path's task, so a signed
 * result can't be replayed onto another task. {@code agentStatus} is COMPLETED or FAILED;
 * {@code resultPayloadJson} is the (string-encoded) result body; {@code resultUrl} and
 * {@code message} are optional.
 */
public record AgentResultCallbackRequest(
        @NotNull UUID taskId,
        @NotBlank String agentStatus,
        @NotBlank String resultPayloadJson,
        @Size(max = 2000) String resultUrl,
        @Size(max = 2000) String message
) {
}
