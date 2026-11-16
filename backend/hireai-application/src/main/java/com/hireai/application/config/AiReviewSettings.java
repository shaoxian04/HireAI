package com.hireai.application.config;

import java.time.Duration;

/**
 * How AI Review runs are retried (spec S10): a review still REQUESTED {@code retryAfter} after it was
 * last tried is run again by the sweeper, and fails (fee refunded) after {@code maxAttempts}.
 */
public record AiReviewSettings(int maxAttempts, Duration retryAfter) {

    public AiReviewSettings {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("hireai.ai-review.max-attempts must be at least 1");
        }
        if (retryAfter == null || retryAfter.isNegative() || retryAfter.isZero()) {
            throw new IllegalArgumentException("hireai.ai-review.retry-after must be positive");
        }
    }
}
