package com.hireai.domain.biz.task.info;

import com.hireai.domain.biz.task.item.info.AgentItemResultInfo;

import java.util.List;

/**
 * Domain-layer carrier for an agent's result callback. Assembled by the callback controller
 * from the validated request body and passed to the application layer.
 * {@code agentStatus} is one of COMPLETED / FAILED. {@code items} holds the per-Item results of a
 * multi-Item Task (spec S3); empty for a single-deliverable Task.
 */
public record AgentResultInfo(String agentStatus, String resultPayloadJson,
                              String resultUrl, String message, List<AgentItemResultInfo> items) {

    public AgentResultInfo {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public AgentResultInfo(String agentStatus, String resultPayloadJson, String resultUrl, String message) {
        this(agentStatus, resultPayloadJson, resultUrl, message, List.of());
    }
}
