package com.hireai.domain.biz.task.info;

import com.hireai.domain.biz.acceptance.check.model.SelfReportedAnomaly;

import java.util.List;

/**
 * Domain-layer carrier for an agent's result callback. Assembled by the callback controller
 * from the validated request body and passed to the application layer.
 * {@code agentStatus} is one of COMPLETED / FAILED. {@code anomalies} are what the Agent
 * self-reported with the result (spec D7); never null.
 */
public record AgentResultInfo(String agentStatus, String resultPayloadJson,
                              String resultUrl, String message, List<SelfReportedAnomaly> anomalies) {

    public AgentResultInfo {
        anomalies = anomalies == null ? List.of() : List.copyOf(anomalies);
    }

    /** A result reported without anomalies. */
    public AgentResultInfo(String agentStatus, String resultPayloadJson, String resultUrl, String message) {
        this(agentStatus, resultPayloadJson, resultUrl, message, List.of());
    }
}
