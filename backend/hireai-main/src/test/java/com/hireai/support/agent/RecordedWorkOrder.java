package com.hireai.support.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One WorkOrder exactly as the fake Agent received it: every request header (looked up
 * case-insensitively) and the raw body bytes, untouched. JSON accessors are conveniences over
 * those bytes; assert on {@link #body()} when the exact bytes matter (e.g. signature checks).
 */
public final class RecordedWorkOrder {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final int sequence;
    private final Instant receivedAt;
    private final Map<String, List<String>> headers;
    private final byte[] body;

    RecordedWorkOrder(int sequence, Instant receivedAt, Map<String, List<String>> headers, byte[] body) {
        this.sequence = sequence;
        this.receivedAt = receivedAt;
        TreeMap<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.forEach((name, values) -> copy.put(name, List.copyOf(values)));
        this.headers = Collections.unmodifiableMap(copy);
        this.body = body.clone();
    }

    /** Zero-based arrival position at this fake Agent. */
    public int sequence() {
        return sequence;
    }

    public Instant receivedAt() {
        return receivedAt;
    }

    /** All headers, keyed case-insensitively. */
    public Map<String, List<String>> headers() {
        return headers;
    }

    /** First value of a header (case-insensitive name), or null when absent. */
    public String header(String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    /** The token after {@code Bearer } in the Authorization header, or null. */
    public String bearerToken() {
        String authorization = header("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return authorization.substring("Bearer ".length());
    }

    /** A copy of the exact body bytes received. */
    public byte[] body() {
        return body.clone();
    }

    public String bodyText() {
        return new String(body, StandardCharsets.UTF_8);
    }

    public JsonNode json() {
        try {
            return JSON.readTree(body);
        } catch (IOException ex) {
            throw new UncheckedIOException("WorkOrder body is not JSON: " + bodyText(), ex);
        }
    }

    /** A top-level text field of the JSON body, or null when absent. */
    public String field(String name) {
        JsonNode node = json().get(name);
        return node == null || node.isNull() ? null : node.asText();
    }

    public String taskId() {
        return field("taskId");
    }

    public String callbackUrl() {
        return field("callbackUrl");
    }
}
