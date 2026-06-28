package com.hireai.domain.biz.routing.service;

import com.hireai.domain.biz.offering.agent.info.AgentCandidate;
import com.hireai.domain.biz.task.info.TaskRoutingView;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain service for the routing MATCH decision. Given a task's routing view and the
 * ACTIVE agent candidates, selects the best-fitting agent version. Framework-free; the
 * bean is registered in DomainServiceConfig. Selection is pure and deterministic so it
 * can be unit-tested without Spring or a database.
 *
 * Selection rules:
 *   - candidate must advertise the task's category (in its capabilityCategories),
 *   - candidate price must be <= the task budget,
 *   - among the survivors, pick the highest reputationScore (tie-break),
 *   - returns the chosen agentVersionId, or empty when no candidate fits.
 */
public interface RoutingMatchDomainService {

    Optional<UUID> selectAgentVersion(TaskRoutingView criteria, List<AgentCandidate> candidates);
}
