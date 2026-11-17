package com.hireai.application.biz.acceptance.extraction;

import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Requirement extraction is capped per user over a sliding window (spec S6; 429 beyond it). */
class ExtractionRateLimiterTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-16T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private final ExtractionRateLimiter limiter = new ExtractionRateLimiter(3, Duration.ofMinutes(1), clock);

    @Test
    void aUserBeyondTheLimitIsRefusedUntilTheWindowMovesOn() {
        UUID user = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            limiter.acquire(user);
        }
        assertThatThrownBy(() -> limiter.acquire(user)).isInstanceOfSatisfying(DomainException.class,
                e -> org.assertj.core.api.Assertions.assertThat(e.resultCode()).isEqualTo(ResultCode.RATE_LIMITED));

        // Another user has their own allowance.
        assertThatCode(() -> limiter.acquire(UUID.randomUUID())).doesNotThrowAnyException();

        now.set(now.get().plus(Duration.ofSeconds(61)));
        assertThatCode(() -> limiter.acquire(user)).doesNotThrowAnyException();
    }

    @Test
    void aNonsenseLimitFailsStartup() {
        assertThatThrownBy(() -> new ExtractionRateLimiter(0, Duration.ofMinutes(1), clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExtractionRateLimiter(1, Duration.ZERO, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
