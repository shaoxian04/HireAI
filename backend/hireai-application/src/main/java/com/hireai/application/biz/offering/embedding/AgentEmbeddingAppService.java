package com.hireai.application.biz.offering.embedding;

import java.util.List;
import java.util.UUID;

/** Keeps each Agent's profile vector in step with its profile text and the configured model. */
public interface AgentEmbeddingAppService {

    /** Agents (not DEACTIVATED) whose vector is missing, from another model, or from older text. */
    List<UUID> agentIdsNeedingEmbedding();

    /**
     * Embeds the Agent's current profile text and stores it, unless the stored vector is already
     * current. Never runs inside the caller's transaction.
     *
     * @throws com.hireai.application.port.embedding.EmbeddingUnavailableException when the provider fails
     */
    void refresh(UUID agentId);
}
