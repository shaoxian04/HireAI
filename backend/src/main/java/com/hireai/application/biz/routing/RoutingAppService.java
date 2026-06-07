package com.hireai.application.biz.routing;

import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

import java.util.UUID;

/**
 * Orchestrates the routing decision for a submitted task. Reads the task routing view,
 * asks the AgentRepository for ACTIVE candidates, runs the match, and either
 * assign-and-queues the task (then publishes a dispatch message AFTER that write has
 * committed) or marks it AWAITING_CAPACITY. Invoked from RoutingEventListener after the
 * submit transaction commits (Hard Invariant #1: routing never precedes a committed escrow
 * freeze).
 */
@Validated
public interface RoutingAppService {

    void route(@NonNull UUID taskId);

    /**
     * Direct booking path: skip the matcher and dispatch to a specific agent version.
     * Follows the same ordering contract as route(): QUEUED commits first (REQUIRES_NEW),
     * then the dispatch message is published.
     */
    void dispatchDirect(@NonNull UUID taskId, @NonNull UUID agentVersionId);
}
