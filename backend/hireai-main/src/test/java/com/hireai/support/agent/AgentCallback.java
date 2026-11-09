package com.hireai.support.agent;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A scripted result callback. Built from the WorkOrder it answers, then adjusted by any
 * {@code with…} overrides, signed, and sent either {@link #beforeReply() before} the fake Agent
 * replies to the WorkOrder (while the platform is still blocked on the send, so the task is not
 * yet EXECUTING) or {@link #afterReply(Duration) after} it (the default, with no delay).
 *
 * <p>Signing (#72): by default the final body bytes are signed with the sending
 * {@link FakeAgent#signingSecret()} at the current wall-clock time, as a well-behaved Agent does
 * ({@code X-HireAI-Signature: t=<ts>,v1=<hex HMAC-SHA256(secret, "<ts>." + body)>}). The platform
 * only accepts it if that secret is the Agent's stored one: see
 * {@code WholeAppIntegrationTest.shareSigningSecret}. Use {@link #unsigned()},
 * {@link #signedWith(String)} or {@link #signedAt(Supplier)} to misbehave; a request that already
 * carries the header (e.g. via {@link #withHeader}) is left as it is.
 *
 * <p>Extension point: {@link #custom(Function)} takes any {@code RecordedWorkOrder ->
 * CallbackRequest} function.
 */
public final class AgentCallback {

    public static final String SIGNATURE_HEADER = "X-HireAI-Signature";

    /** A spec-conforming COMPLETED result for a JSON output spec with no schema. */
    public static final String COMPLETED_BODY = "{\"agentStatus\":\"COMPLETED\","
            + "\"resultPayloadJson\":\"{\\\"summary\\\":\\\"done\\\"}\","
            + "\"resultUrl\":\"https://results.example/r/1\",\"message\":\"ok\"}";

    /** How the callback is signed: whether, with which secret (null = the Agent's), at what time. */
    private record Signing(boolean enabled, String secret, Supplier<Instant> at) {
        static final Signing DEFAULT = new Signing(true, null, Instant::now);
    }

    private final Function<RecordedWorkOrder, CallbackRequest> builder;
    private final boolean beforeReply;
    private final Duration delay;
    private final Map<String, String> headerOverrides;
    private final String bodyOverride;
    private final Signing signing;

    private AgentCallback(Function<RecordedWorkOrder, CallbackRequest> builder, boolean beforeReply,
                          Duration delay, Map<String, String> headerOverrides, String bodyOverride,
                          Signing signing) {
        this.builder = builder;
        this.beforeReply = beforeReply;
        this.delay = delay;
        this.headerOverrides = headerOverrides;
        this.bodyOverride = bodyOverride;
        this.signing = signing;
    }

    /**
     * POST {@link #COMPLETED_BODY} to the WorkOrder's {@code callbackUrl}, presenting the
     * WorkOrder's own bearer token and signed with the Agent's secret (what a well-behaved Agent does).
     */
    public static AgentCallback completed() {
        return custom(order -> CallbackRequest.post(order.callbackUrl())
                .header("Authorization", "Bearer " + order.bearerToken())
                .body(COMPLETED_BODY));
    }

    /** Build the whole callback from the WorkOrder yourself (still signed unless told otherwise). */
    public static AgentCallback custom(Function<RecordedWorkOrder, CallbackRequest> builder) {
        return new AgentCallback(builder, false, Duration.ZERO, Map.of(), null, Signing.DEFAULT);
    }

    /**
     * The {@code X-HireAI-Signature} value an Agent sends: computed here with the JDK's Mac,
     * independently of the platform's own helper.
     */
    public static String signature(String secret, Instant at, byte[] body) {
        long ts = at.getEpochSecond();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((ts + ".").getBytes(StandardCharsets.UTF_8));
            return "t=" + ts + ",v1=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception ex) {
            throw new IllegalStateException("HmacSHA256 unavailable", ex);
        }
    }

    /** Send synchronously before the WorkOrder reply is written. */
    public AgentCallback beforeReply() {
        return new AgentCallback(builder, true, Duration.ZERO, headerOverrides, bodyOverride, signing);
    }

    /** Send {@code delay} after the WorkOrder reply has been written. */
    public AgentCallback afterReply(Duration delay) {
        return new AgentCallback(builder, false, delay, headerOverrides, bodyOverride, signing);
    }

    /** Set or replace a header; a null value removes it (e.g. drop Authorization). */
    public AgentCallback withHeader(String name, String value) {
        Map<String, String> copy = new LinkedHashMap<>(headerOverrides);
        copy.put(name, value);
        return new AgentCallback(builder, beforeReply, delay, copy, bodyOverride, signing);
    }

    public AgentCallback withBody(String body) {
        return new AgentCallback(builder, beforeReply, delay, headerOverrides, body, signing);
    }

    /** Send no {@code X-HireAI-Signature} at all. */
    public AgentCallback unsigned() {
        return new AgentCallback(builder, beforeReply, delay, headerOverrides, bodyOverride,
                new Signing(false, null, signing.at()));
    }

    /** Sign with {@code secret} instead of the Agent's own. */
    public AgentCallback signedWith(String secret) {
        return new AgentCallback(builder, beforeReply, delay, headerOverrides, bodyOverride,
                new Signing(true, secret, signing.at()));
    }

    /** Sign with the timestamp {@code at} supplies (read when the callback is sent). */
    public AgentCallback signedAt(Supplier<Instant> at) {
        return new AgentCallback(builder, beforeReply, delay, headerOverrides, bodyOverride,
                new Signing(signing.enabled(), signing.secret(), at));
    }

    boolean sentBeforeReply() {
        return beforeReply;
    }

    Duration delay() {
        return delay;
    }

    CallbackRequest buildFor(RecordedWorkOrder order, String agentSecret) {
        CallbackRequest request = builder.apply(order);
        for (Map.Entry<String, String> override : headerOverrides.entrySet()) {
            request = request.header(override.getKey(), override.getValue());
        }
        if (bodyOverride != null) {
            request = request.body(bodyOverride);
        }
        boolean alreadySigned = request.headers().keySet().stream().anyMatch(SIGNATURE_HEADER::equalsIgnoreCase);
        if (signing.enabled() && !alreadySigned) {
            String secret = signing.secret() != null ? signing.secret() : agentSecret;
            request = request.header(SIGNATURE_HEADER, signature(secret, signing.at().get(), request.bodyBytes()));
        }
        return request;
    }
}
