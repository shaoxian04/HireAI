package com.hireai.support.agent;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * What the fake Agent does with one WorkOrder: the HTTP status and body it replies with, an
 * optional delay (or an indefinite hang) before replying, and an optional result callback.
 * Immutable; every {@code with…} method returns a new reply.
 */
public final class FakeAgentReply {

    private final int status;
    private final String body;
    private final Duration delay;
    private final boolean hang;
    private final AgentCallback callback;
    private final Map<String, String> headers;

    private FakeAgentReply(int status, String body, Duration delay, boolean hang, AgentCallback callback,
                           Map<String, String> headers) {
        this.status = status;
        this.body = body;
        this.delay = delay;
        this.hang = hang;
        this.callback = callback;
        this.headers = Map.copyOf(headers);
    }

    /** Reply immediately with {@code status} and an empty JSON object body. */
    public static FakeAgentReply status(int status) {
        return new FakeAgentReply(status, "{}", Duration.ZERO, false, null, Map.of());
    }

    public static FakeAgentReply ok() {
        return status(200);
    }

    public static FakeAgentReply accepted() {
        return status(202);
    }

    /**
     * Never reply until {@link FakeAgent#releaseHangs()} or {@link FakeAgent#close()}; the
     * platform's read timeout fires first. Released hangs then reply 200.
     */
    public static FakeAgentReply hang() {
        return new FakeAgentReply(200, "{}", Duration.ZERO, true, null, Map.of());
    }

    public FakeAgentReply withBody(String body) {
        return new FakeAgentReply(status, body, delay, hang, callback, headers);
    }

    /** Wait this long before replying (cut short by {@link FakeAgent#close()}). */
    public FakeAgentReply afterDelay(Duration delay) {
        return new FakeAgentReply(status, body, delay, hang, callback, headers);
    }

    /** Also send this result callback (before or after the reply, as the callback says). */
    public FakeAgentReply withCallback(AgentCallback callback) {
        return new FakeAgentReply(status, body, delay, hang, callback, headers);
    }

    /** Also send this response header (e.g. {@code Location} on a 3xx). */
    public FakeAgentReply withHeader(String name, String value) {
        Map<String, String> more = new LinkedHashMap<>(headers);
        more.put(name, value);
        return new FakeAgentReply(status, body, delay, hang, callback, more);
    }

    Map<String, String> headers() {
        return headers;
    }

    int status() {
        return status;
    }

    String body() {
        return body;
    }

    Duration delay() {
        return delay;
    }

    boolean hangs() {
        return hang;
    }

    Optional<AgentCallback> callback() {
        return Optional.ofNullable(callback);
    }
}
