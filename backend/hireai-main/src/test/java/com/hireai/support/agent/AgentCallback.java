package com.hireai.support.agent;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * A scripted result callback. Built from the WorkOrder it answers, then adjusted by any
 * {@code with…} overrides, and sent either {@link #beforeReply() before} the fake Agent replies
 * to the WorkOrder (while the platform is still blocked on the send, so the task is not yet
 * EXECUTING) or {@link #afterReply(Duration) after} it (the default, with no delay).
 *
 * <p>Extension point: {@link #custom(Function)} takes any {@code RecordedWorkOrder ->
 * CallbackRequest} function, e.g. one that signs the exact body bytes with an Agent secret.
 */
public final class AgentCallback {

    /** A spec-conforming COMPLETED result for a JSON output spec with no schema. */
    public static final String COMPLETED_BODY = "{\"agentStatus\":\"COMPLETED\","
            + "\"resultPayloadJson\":\"{\\\"summary\\\":\\\"done\\\"}\","
            + "\"resultUrl\":\"https://results.example/r/1\",\"message\":\"ok\"}";

    private final Function<RecordedWorkOrder, CallbackRequest> builder;
    private final boolean beforeReply;
    private final Duration delay;
    private final Map<String, String> headerOverrides;
    private final String bodyOverride;

    private AgentCallback(Function<RecordedWorkOrder, CallbackRequest> builder, boolean beforeReply,
                          Duration delay, Map<String, String> headerOverrides, String bodyOverride) {
        this.builder = builder;
        this.beforeReply = beforeReply;
        this.delay = delay;
        this.headerOverrides = headerOverrides;
        this.bodyOverride = bodyOverride;
    }

    /**
     * POST {@link #COMPLETED_BODY} to the WorkOrder's {@code callbackUrl}, presenting the
     * WorkOrder's own bearer token (what a well-behaved Agent does).
     */
    public static AgentCallback completed() {
        return custom(order -> CallbackRequest.post(order.callbackUrl())
                .header("Authorization", "Bearer " + order.bearerToken())
                .body(COMPLETED_BODY));
    }

    /** Build the whole callback from the WorkOrder yourself. */
    public static AgentCallback custom(Function<RecordedWorkOrder, CallbackRequest> builder) {
        return new AgentCallback(builder, false, Duration.ZERO, Map.of(), null);
    }

    /** Send synchronously before the WorkOrder reply is written. */
    public AgentCallback beforeReply() {
        return new AgentCallback(builder, true, Duration.ZERO, headerOverrides, bodyOverride);
    }

    /** Send {@code delay} after the WorkOrder reply has been written. */
    public AgentCallback afterReply(Duration delay) {
        return new AgentCallback(builder, false, delay, headerOverrides, bodyOverride);
    }

    /** Set or replace a header; a null value removes it (e.g. drop Authorization). */
    public AgentCallback withHeader(String name, String value) {
        Map<String, String> copy = new LinkedHashMap<>(headerOverrides);
        copy.put(name, value);
        return new AgentCallback(builder, beforeReply, delay, copy, bodyOverride);
    }

    public AgentCallback withBody(String body) {
        return new AgentCallback(builder, beforeReply, delay, headerOverrides, body);
    }

    boolean sentBeforeReply() {
        return beforeReply;
    }

    Duration delay() {
        return delay;
    }

    CallbackRequest buildFor(RecordedWorkOrder order) {
        CallbackRequest request = builder.apply(order);
        for (Map.Entry<String, String> override : headerOverrides.entrySet()) {
            request = request.header(override.getKey(), override.getValue());
        }
        if (bodyOverride != null) {
            request = request.body(bodyOverride);
        }
        return request;
    }
}
