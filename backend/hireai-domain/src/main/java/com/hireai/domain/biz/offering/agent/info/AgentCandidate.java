package com.hireai.domain.biz.offering.agent.info;

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
 *
 * <p>{@code maxConcurrent}/{@code inFlight}/{@code sampleCount} feed the multi-factor scorer
 * (loadHeadroom + exploration terms); counts are per-AGENT across all its versions, derived
 * fresh by the candidate query.
 *
 * <p>{@code similarity} is the RAW cosine similarity between the task text and the Agent's profile
 * (#85), computed by the candidate query from vectors of the same model. Null when the Agent has no
 * vector of that model, or when the task text couldn't be embedded; the matcher then treats the Agent
 * as neutral (it passes the floor and scores 0.5 on relevance).
 */
public record AgentCandidate(UUID agentId, UUID agentVersionId, List<String> capabilityCategories,
                             BigDecimal price, String webhookUrl, int maxExecutionSeconds,
                             BigDecimal reputationScore, String outputSpecJson,
                             int maxConcurrent, long inFlight, long sampleCount, Double similarity) {

    /** A candidate without a relevance signal (no task or Agent vector). */
    public AgentCandidate(UUID agentId, UUID agentVersionId, List<String> capabilityCategories,
                          BigDecimal price, String webhookUrl, int maxExecutionSeconds,
                          BigDecimal reputationScore, String outputSpecJson,
                          int maxConcurrent, long inFlight, long sampleCount) {
        this(agentId, agentVersionId, capabilityCategories, price, webhookUrl, maxExecutionSeconds,
                reputationScore, outputSpecJson, maxConcurrent, inFlight, sampleCount, null);
    }
}
