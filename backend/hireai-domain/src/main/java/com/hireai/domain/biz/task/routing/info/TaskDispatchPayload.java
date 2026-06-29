package com.hireai.domain.biz.task.routing.info;

/**
 * The task content carried to an agent for execution. Framework-free. JSON-bearing
 * fields ({@code expectedDeliverableJson}, {@code outputSpecJson}) are passed through
 * as opaque strings so the domain never parses agent-facing JSON. Built by the routing
 * orchestration (Plan 4) and serialised into the webhook request by the dispatch client
 * (Plan 2); {@code callbackUrl} is where the agent posts its result back.
 */
public record TaskDispatchPayload(String title, String description, String category,
                                  String expectedDeliverableJson, String outputSpecJson,
                                  String callbackUrl) {
}
