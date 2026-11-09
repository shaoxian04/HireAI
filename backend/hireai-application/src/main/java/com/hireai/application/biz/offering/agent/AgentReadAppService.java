package com.hireai.application.biz.offering.agent;

import com.hireai.domain.biz.offering.agent.model.AgentModel;
import com.hireai.domain.biz.offering.agent.repository.AgentQuery;
import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrates agent READ use cases. Enforces Invariant #5 (server-side identity +
 * ownership): an agent is only returned to its owner; otherwise NOT_FOUND, so existence is
 * not leaked across builders.
 */
@Validated
public interface AgentReadAppService {

    AgentModel getForOwner(@NonNull UUID agentId, @NonNull UUID ownerId);

    List<AgentModel> listForOwner(@NonNull UUID ownerId, @NonNull AgentQuery query);

    /**
     * The subset of these agents whose storefront is listed. Matching (shortlist, routing, failover)
     * considers only listed Agents, so the Builder dashboard warns about the others.
     */
    Set<UUID> findListedAgentIds(@NonNull Collection<UUID> agentIds);

    /**
     * Platform-internal: the AgentSigningSecret used to sign a WorkOrder for this version (empty
     * when the agent has none yet). Never expose it over HTTP or put it on a model or DTO.
     */
    Optional<String> findSigningSecretForVersion(@NonNull UUID agentVersionId);
}
