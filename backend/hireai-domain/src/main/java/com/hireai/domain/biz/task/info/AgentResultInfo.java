package com.hireai.domain.biz.task.info;

import com.hireai.domain.biz.acceptance.check.model.SelfReportedAnomaly;
import com.hireai.domain.biz.task.item.info.AgentItemResultInfo;

import java.util.List;

/**
 * Domain-layer carrier for an agent's result callback. Assembled by the callback controller
 * from the validated request body and passed to the application layer.
 * {@code agentStatus} is one of COMPLETED / FAILED. {@code items} holds the per-Item results of a
 * multi-Item Task (spec S3); empty for a single-deliverable Task. {@code anomalies} are what the
 * Agent self-reported with the result (spec D7); never null.
 */
public record AgentResultInfo(String agentStatus, String resultPayloadJson,
                              String resultUrl, String message, List<AgentItemResultInfo> items,
                              List<SelfReportedAnomaly> anomalies) {

    public AgentResultInfo {
        items = items == null ? List.of() : List.copyOf(items);
        anomalies = anomalies == null ? List.of() : List.copyOf(anomalies);
    }

    /** A multi-Item result, or a single one without anomalies. */
    public AgentResultInfo(String agentStatus, String resultPayloadJson, String resultUrl, String message,
                           List<AgentItemResultInfo> items) {
        this(agentStatus, resultPayloadJson, resultUrl, message, items, List.of());
    }

    public AgentResultInfo(String agentStatus, String resultPayloadJson, String resultUrl, String message) {
        this(agentStatus, resultPayloadJson, resultUrl, message, List.of(), List.of());
    }
}
