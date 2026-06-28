package com.hireai.domain.biz.offering.storefront.repository;

import com.hireai.domain.biz.offering.storefront.model.StorefrontModel;

import java.util.Optional;
import java.util.UUID;

/** Persistence contract for the Storefront aggregate (1:1 with the Agent root, table agent_profiles). */
public interface StorefrontRepository {

    StorefrontModel save(StorefrontModel storefront);

    Optional<StorefrontModel> findByAgentId(UUID agentId);
}
