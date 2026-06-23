package com.hireai.application.port.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read-side rows for the builder earnings view. Returns EVERY task routed to any agent
 * the owner owns (the JOIN through agent_version_id excludes unrouted tasks naturally);
 * status/resolution filtering and all money arithmetic happen in the app service so the
 * semantics live in testable Java, not SQL.
 */
public interface BuilderEarningsQueryPort {

    List<RoutedTaskRow> routedTasks(UUID ownerId);

    /** Every agent the owner owns — so agents with no routed tasks still get a zero row. */
    List<OwnedAgentRow> ownedAgents(UUID ownerId);

    record RoutedTaskRow(UUID taskId, String title, BigDecimal budget, String status,
                         String resolution, Instant resolvedAt, UUID agentId, String agentName) {
    }

    record OwnedAgentRow(UUID agentId, String agentName) {
    }
}
