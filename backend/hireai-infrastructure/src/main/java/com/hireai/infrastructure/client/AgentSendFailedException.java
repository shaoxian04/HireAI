package com.hireai.infrastructure.client;

import java.util.Optional;

/**
 * The WorkOrder HTTP send itself failed, in a way the Agent is accountable for: its endpoint
 * refused or could not be reached, did not answer within the read timeout (the contract is a 2xx
 * within 15s), or answered with a non-2xx status. Only this failure consumes one of the
 * dispatch's attempts; platform-side failures (task lookup, token issue, an invalid URL) never
 * do. {@link #statusCode()} is the Agent's reply status, empty for an I/O failure. A busy reply
 * (429/503) is the subclass {@link AgentBusyException}, which consumes no attempt.
 */
public class AgentSendFailedException extends RuntimeException {

    private final Integer statusCode;

    public AgentSendFailedException(String message, Integer statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public Optional<Integer> statusCode() {
        return Optional.ofNullable(statusCode);
    }
}
