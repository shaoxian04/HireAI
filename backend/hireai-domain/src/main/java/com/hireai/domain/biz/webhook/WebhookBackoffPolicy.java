package com.hireai.domain.biz.webhook;

import java.time.Instant;

/** Exponential-with-cap retry schedule for webhook deliveries. Pure; config supplies the bounds. */
public final class WebhookBackoffPolicy {
    private final long baseSeconds;
    private final long capSeconds;
    private final int maxAttempts;

    public WebhookBackoffPolicy(long baseSeconds, long capSeconds, int maxAttempts) {
        this.baseSeconds = baseSeconds;
        this.capSeconds = capSeconds;
        this.maxAttempts = maxAttempts;
    }

    /** True once {@code attempts} failures have occurred and no more retries remain. */
    public boolean exhausted(int attempts) {
        return attempts >= maxAttempts;
    }

    /** Delay after the {@code attempts}-th failure (1-based), capped; overflow-safe. */
    public Instant nextAttempt(int attempts, Instant now) {
        int shift = Math.max(0, attempts - 1);
        long delay = (shift >= 20) ? capSeconds : Math.min(capSeconds, baseSeconds << shift);
        return now.plusSeconds(delay);
    }
}
