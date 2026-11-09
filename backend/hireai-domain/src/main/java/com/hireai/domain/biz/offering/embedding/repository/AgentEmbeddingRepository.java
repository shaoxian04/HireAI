package com.hireai.domain.biz.offering.embedding.repository;

import com.hireai.domain.biz.offering.embedding.info.AgentEmbeddingSource;
import com.hireai.domain.biz.offering.embedding.model.AgentEmbeddingModel;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentEmbeddingRepository {

    /** The Agent's profile fields and its stored vector's tags; empty when the Agent doesn't exist. */
    Optional<AgentEmbeddingSource> findSource(UUID agentId);

    /** Every Agent that isn't DEACTIVATED, with its stored vector's tags. */
    List<AgentEmbeddingSource> findSourcesOfLiveAgents();

    /** Inserts or replaces the Agent's vector. */
    void save(AgentEmbeddingModel embedding);
}
