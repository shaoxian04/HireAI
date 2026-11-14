package com.hireai.domain.biz.offering.storefront.repository;

import com.hireai.domain.biz.offering.storefront.model.StorefrontModel;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Persistence contract for the Storefront aggregate (1:1 with the Agent root, table agent_profiles). */
public interface StorefrontRepository {

    StorefrontModel save(StorefrontModel storefront);

    Optional<StorefrontModel> findByAgentId(UUID agentId);

    /** The subset of {@code agentIds} whose storefront is listed (only listed Agents are matched). */
    Set<UUID> findListedAgentIds(Collection<UUID> agentIds);
}
