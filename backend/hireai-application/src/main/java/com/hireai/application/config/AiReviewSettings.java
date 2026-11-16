package com.hireai.application.config;

import java.time.Duration;

/**
 * How AI Review runs are retried (spec S10). A run tries up to {@code maxAttempts} times in all: an
 * unusable answer is retried once, a transient failure after {@code retryBackoff}, doubling each time.
 * A review still REQUESTED {@code retryAfter} after it was requested (the process stopped mid-run) is
 * run again by the sweeper.
 */
public record AiReviewSettings(int maxAttempts, Duration retryAfter, Duration retryBackoff) {

    public AiReviewSettings {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("hireai.ai-review.max-attempts must be at least 1");
        }
        if (retryAfter == null || retryAfter.isNegative() || retryAfter.isZero()) {
            throw new IllegalArgumentException("hireai.ai-review.retry-after must be positive");
        }
        if (retryBackoff == null || retryBackoff.isNegative()) {
            throw new IllegalArgumentException("hireai.ai-review.retry-backoff must not be negative");
        }
    }
}
