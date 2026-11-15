package com.hireai.controller.biz.agentcallback.dto;

import com.hireai.domain.biz.task.rejection.enums.AgentResponse;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Inbound HTTP DTO for an Agent's response to a rejection notice (#95). {@code taskId} is covered by
 * the signature and must equal the path's task. {@code action} is REVISE, CONCEDE or STAND_BY;
 * {@code reason} is the stand-by reason shown to the Client; a REVISE carries the revised result in
 * {@code resultPayloadJson} and/or {@code resultUrl}, as on a result callback.
 */
public record AgentRejectionResponseRequest(
        @NotNull UUID taskId,
        @NotNull AgentResponse action,
        @Size(max = 500) String reason,
        String resultPayloadJson,
        @Size(max = 2000) String resultUrl
) {
}
