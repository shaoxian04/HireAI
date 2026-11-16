package com.hireai.domain.biz.acceptance.engagement.repository;

import com.hireai.domain.biz.acceptance.engagement.model.EngagementModel;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link EngagementModel}. */
public interface EngagementRepository {

    /** Inserts a new Engagement; a second open one for the same buyer and Agent is refused (409). */
    void insert(EngagementModel engagement);

    /** Writes a transition (status, acceptance, end). */
    void update(EngagementModel engagement);

    Optional<EngagementModel> findById(UUID id);

    /** The buyer's Engagements, newest first. */
    List<EngagementModel> findByClientId(UUID clientId);

    /** The Engagements on these Agents, newest first. */
    List<EngagementModel> findByAgentIds(Collection<UUID> agentIds);
}
