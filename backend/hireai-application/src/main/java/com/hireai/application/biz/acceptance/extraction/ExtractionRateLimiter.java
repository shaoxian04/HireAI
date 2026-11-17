package com.hireai.application.biz.acceptance.extraction;

import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caps the LLM calls requirement extraction makes for one user (spec S6): {@code POST /api/contracts/draft}
 * and every description-only submit each make one, so without a cap one caller could run up the provider
 * bill. A sliding window of {@code hireai.extraction.rate-limit.max-calls} per
 * {@code hireai.extraction.rate-limit.window}; beyond it the call is refused (429, {@code RATE_LIMITED}) before
 * anything is extracted or frozen. Held in memory, per backend instance.
 */
@Component
public class ExtractionRateLimiter {

    private final int maxCalls;
    private final Duration window;
    private final Clock clock;
    private final Map<UUID, Deque<Instant>> calls = new ConcurrentHashMap<>();

    public ExtractionRateLimiter(@Value("${hireai.extraction.rate-limit.max-calls:20}") int maxCalls,
                                 @Value("${hireai.extraction.rate-limit.window:PT1M}") Duration window,
                                 Clock clock) {
        if (maxCalls < 1 || window == null || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("extraction rate limit needs max-calls >= 1 and a positive window");
        }
        this.maxCalls = maxCalls;
        this.window = window;
        this.clock = clock;
    }

    /** Counts one extraction for {@code userId}, or refuses it (RATE_LIMITED) when the window is full. */
    public void acquire(UUID userId) {
        Instant now = clock.instant();
        Deque<Instant> mine = calls.computeIfAbsent(userId, id -> new ArrayDeque<>());
        synchronized (mine) {
            while (!mine.isEmpty() && !mine.peekFirst().isAfter(now.minus(window))) {
                mine.pollFirst();
            }
            if (mine.size() >= maxCalls) {
                Instant retryAt = mine.peekFirst().plus(window);
                throw new DomainException(ResultCode.RATE_LIMITED, "Too many requirement extractions: at most "
                        + maxCalls + " per " + window + ". Try again after " + retryAt
                        + ", or submit with a contract (no extraction)");
            }
            mine.addLast(now);
        }
    }
}
