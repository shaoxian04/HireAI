package com.hireai.domain.biz.task.item.info;

import com.hireai.domain.biz.acceptance.check.model.SelfReportedAnomaly;

import java.util.List;

/**
 * One Item's result inside an Agent's callback for a multi-Item Task: which Item
 * ({@code itemNo}, from the WorkOrder), COMPLETED or FAILED, its payload / link, and the anomalies the
 * Agent self-reported about this Item's source (spec D7), such as an invoice whose figures don't add up.
 */
public record AgentItemResultInfo(int itemNo, String agentStatus, String resultPayloadJson, String resultUrl,
                                  List<SelfReportedAnomaly> anomalies) {

    public AgentItemResultInfo {
        anomalies = anomalies == null ? List.of() : List.copyOf(anomalies);
    }

    public AgentItemResultInfo(int itemNo, String agentStatus, String resultPayloadJson, String resultUrl) {
        this(itemNo, agentStatus, resultPayloadJson, resultUrl, List.of());
    }
}
