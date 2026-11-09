package com.hireai.infrastructure.client;

import java.util.Set;

/**
 * The Agent declined the WorkOrder as busy: it replied 429 or 503, which the acknowledgement
 * contract defines as "at capacity, not now". Unlike its parent this is not a fault: the dispatch
 * consumer never retries it and never records a reputation event for it.
 */
public class AgentBusyException extends AgentSendFailedException {

    private static final Set<Integer> BUSY_STATUSES = Set.of(429, 503);

    public AgentBusyException(String message, int statusCode) {
        super(message, statusCode, null);
    }

    /** {@code true} for the statuses the acknowledgement contract reserves for "busy". */
    public static boolean isBusyStatus(int statusCode) {
        return BUSY_STATUSES.contains(statusCode);
    }
}
