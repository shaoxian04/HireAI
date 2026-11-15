package com.hireai.domain.biz.task.item.info;

/**
 * One Item's result inside an Agent's callback for a multi-Item Task: which Item
 * ({@code itemNo}, from the WorkOrder), COMPLETED or FAILED, and its payload / link.
 */
public record AgentItemResultInfo(int itemNo, String agentStatus, String resultPayloadJson, String resultUrl) {
}
