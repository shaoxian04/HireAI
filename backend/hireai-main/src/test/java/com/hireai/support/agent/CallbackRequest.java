package com.hireai.support.agent;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The exact HTTP POST a fake Agent sends as its result callback: target URL, headers and raw
 * body bytes. Immutable; {@code header}/{@code body} return a new request.
 */
public final class CallbackRequest {

    private final String url;
    private final Map<String, String> headers;
    private final byte[] body;

    private CallbackRequest(String url, Map<String, String> headers, byte[] body) {
        this.url = url;
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        this.body = body.clone();
    }

    public static CallbackRequest post(String url) {
        return new CallbackRequest(url, Map.of("Content-Type", "application/json"), new byte[0]);
    }

    /** Set (or replace) a header. A null value removes it. */
    public CallbackRequest header(String name, String value) {
        Map<String, String> copy = new LinkedHashMap<>(headers);
        copy.keySet().removeIf(existing -> existing.equalsIgnoreCase(name));
        if (value != null) {
            copy.put(name, value);
        }
        return new CallbackRequest(url, copy, body);
    }

    public CallbackRequest body(String body) {
        return body(body.getBytes(StandardCharsets.UTF_8));
    }

    public CallbackRequest body(byte[] body) {
        return new CallbackRequest(url, headers, body);
    }

    public String url() {
        return url;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public byte[] bodyBytes() {
        return body.clone();
    }

    public String bodyText() {
        return new String(body, StandardCharsets.UTF_8);
    }
}
