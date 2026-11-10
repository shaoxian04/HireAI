package com.hireai.infrastructure.client;

import java.time.Duration;

/**
 * The WorkOrder send's connect and read timeouts ({@code hireai.dispatch.connect-timeout} /
 * {@code read-timeout}). The read timeout is the Agent's acknowledgement window: reply 2xx within it.
 * One bean, used by the dispatch HTTP client and by the retry budget check
 * ({@code DispatchRetrySchedule}), so the two can never disagree.
 */
public record AgentDispatchTimeouts(Duration connect, Duration read) {

    public AgentDispatchTimeouts {
        if (connect == null || read == null || connect.isNegative() || connect.isZero()
                || read.isNegative() || read.isZero()) {
            throw new IllegalStateException("hireai.dispatch.connect-timeout and read-timeout must be positive; got "
                    + connect + " / " + read);
        }
    }

    /** The longest one send attempt can take before it counts as failed. */
    public Duration perAttempt() {
        return connect.plus(read);
    }
}
