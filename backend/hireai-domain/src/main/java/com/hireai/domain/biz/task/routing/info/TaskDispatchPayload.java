package com.hireai.domain.biz.task.routing.info;

import java.time.Instant;

/**
 * The task content carried to an agent for execution. Framework-free. {@code outputSpecJson}
 * is passed through as an opaque string so the domain never parses agent-facing JSON. Built by
 * the routing orchestration from the task (title, description and category are the client's
 * own) and serialised into the WorkOrder by the dispatch client. {@code executionDeadline} is
 * the instant stamped on the task at assignment; {@code callbackUrl} is where the agent posts
 * its result back. Nothing about the client (identity, budget) is carried.
 */
public record TaskDispatchPayload(String title, String description, String category,
                                  String outputSpecJson, Instant executionDeadline,
                                  String callbackUrl) {
}
