package com.hireai.domain.biz.task.info;

/**
 * Domain-layer carrier for an agent's result callback. Assembled by the callback controller
 * from the validated request body and passed to the application layer.
 * {@code agentStatus} is one of COMPLETED / FAILED.
 */
public record AgentResultInfo(String agentStatus, String resultPayloadJson,
                              String resultUrl, String message) {
}
