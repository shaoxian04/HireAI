package com.hireai.domain.biz.agent.info;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Read-model carrier describing one ACTIVE agent version eligible to execute a task.
 * Framework-free (the domain layer has zero framework imports). Produced by
 * {@code AgentRepository.findActiveCandidates} (Plan 1) and consumed by the routing
 * matcher (Plan 4) to pick the best agent for a submitted task.
 *
 * <p>{@code outputSpecJson} is the chosen version's declared {@code output_spec} as stored
 * (opaque JSON). It is irrelevant to selection but must be threaded through routing so the
 * winning agent's binding output contract reaches the dispatch payload (Hard Invariant #4).
 */
public record AgentCandidate(UUID agentId, UUID agentVersionId, List<String> capabilityCategories,
                             BigDecimal price, String webhookUrl, int maxExecutionSeconds,
                             BigDecimal reputationScore, String outputSpecJson) {
}
