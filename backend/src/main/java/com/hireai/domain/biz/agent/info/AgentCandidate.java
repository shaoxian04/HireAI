package com.hireai.domain.biz.agent.info;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Read-model carrier describing one ACTIVE agent version eligible to execute a task.
 * Framework-free (the domain layer has zero framework imports). Produced by
 * {@code AgentRepository.findActiveCandidates} (Plan 1) and consumed by the routing
 * matcher (Plan 4) to pick the best agent for a submitted task.
 */
public record AgentCandidate(UUID agentId, UUID agentVersionId, List<String> capabilityCategories,
                             BigDecimal price, String webhookUrl, int maxExecutionSeconds,
                             BigDecimal reputationScore) {
}
