package com.hireai.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * The Agent refused this task's Contract (#93, D33): it replied 422 with
 * {@code {"decline":"CONTRACT","reason":"…"}}. Like {@link AgentBusyException} this is not a fault:
 * the dispatch consumer never retries it and never records a reputation event for it. A 422 whose body
 * is not that object is an ordinary failed send.
 */
public class AgentContractDeclinedException extends AgentSendFailedException {

    public static final int STATUS = 422;
    /** The most of the reply body read when looking for the decline object. */
    public static final int MAX_BODY_BYTES = 8 * 1024;

    private final String reason;

    public AgentContractDeclinedException(String message, String reason) {
        super(message, STATUS, null);
        this.reason = reason;
    }

    /** The Agent's reason, as sent (untrusted; the domain model trims and caps it). May be null. */
    public String reason() {
        return reason;
    }

    /** A parsed contract-decline body; {@code reason} is null when the Agent gave none. */
    public record Decline(String reason) {
    }

    /**
     * Reads a 422 reply body: a {@link Decline} when it is a contract decline ({@code reason} absent or
     * not a string means no reason), empty when it is anything else.
     */
    public static Optional<Decline> parse(byte[] body, ObjectMapper objectMapper) {
        if (body == null || body.length == 0) {
            return Optional.empty();
        }
        JsonNode json;
        try {
            json = objectMapper.readTree(body);
        } catch (Exception ex) {
            return Optional.empty();
        }
        if (json == null || !json.isObject() || !"CONTRACT".equals(json.path("decline").asText(null))) {
            return Optional.empty();
        }
        JsonNode reason = json.get("reason");
        return Optional.of(new Decline(reason != null && reason.isTextual() ? reason.asText() : null));
    }
}
