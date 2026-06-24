package com.hireai.domain.biz.agent.repository;

import com.hireai.domain.biz.agent.model.AgentProfileModel;

import java.util.Optional;
import java.util.UUID;

/** Persistence contract for the storefront profile (1:1 with the Agent root). */
public interface AgentProfileRepository {

    AgentProfileModel save(AgentProfileModel profile);

    Optional<AgentProfileModel> findByAgentId(UUID agentId);
}
