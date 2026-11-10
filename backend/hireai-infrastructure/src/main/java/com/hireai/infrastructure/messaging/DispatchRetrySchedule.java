package com.hireai.infrastructure.messaging;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * How often, and how long apart, a failed WorkOrder send is retried. The configured delays
 * ({@code hireai.dispatch.retry-delays}) are the waits between attempts, so N delays give N + 1
 * attempts in total. Startup fails if a delay is not positive, or if the delays add up to more
 * than the execution grace period ({@code hireai.execution.grace-seconds}): the grace is added
 * to every execution deadline, so retries can then never eat into the Agent's declared
 * execution time.
 */
@Component
public class DispatchRetrySchedule {

    private final List<Duration> delays;
    private final int platformRetries;

    /** Unit-test form: no platform-side re-schedules. */
    public DispatchRetrySchedule(List<Duration> delays, long graceSeconds) {
        this(delays, 0, graceSeconds);
    }

    @Autowired
    public DispatchRetrySchedule(
            @Value("${hireai.dispatch.retry-delays:5s,10s,15s}") List<Duration> delays,
            @Value("${hireai.dispatch.platform-retries:2}") int platformRetries,
            @Value("${hireai.execution.grace-seconds:60}") long graceSeconds) {
        if (platformRetries < 0) {
            throw new IllegalStateException("hireai.dispatch.platform-retries must be >= 0, got " + platformRetries);
        }
        this.platformRetries = platformRetries;
        this.delays = List.copyOf(delays);
        Duration total = Duration.ZERO;
        for (Duration delay : this.delays) {
            if (delay.isNegative() || delay.isZero()) {
                throw new IllegalStateException(
                        "hireai.dispatch.retry-delays must all be positive, got " + this.delays);
            }
            total = total.plus(delay);
        }
        Duration grace = Duration.ofSeconds(graceSeconds);
        if (total.compareTo(grace) > 0) {
            throw new IllegalStateException("hireai.dispatch.retry-delays add up to " + total
                    + ", more than the execution grace period hireai.execution.grace-seconds (" + grace + ")");
        }
    }

    /** Attempts in total, the first send included. */
    public int maxAttempts() {
        return delays.size() + 1;
    }

    /**
     * The wait before retrying after attempt {@code attempt} (1-based) failed, or empty when
     * that was the last attempt.
     */
    public Optional<Duration> delayAfterFailedAttempt(int attempt) {
        if (attempt < 1 || attempt > delays.size()) {
            return Optional.empty();
        }
        return Optional.of(delays.get(attempt - 1));
    }

    /**
     * The wait before re-scheduling a send after the {@code retriesSoFar}-th platform-side failure
     * (task lookup, token issue, secret lookup, ...), or empty once the bound
     * ({@code hireai.dispatch.platform-retries}) is used up or there is no retry queue to wait on.
     * It reuses the shortest retry queue; the Agent's attempt number is unchanged.
     */
    public Optional<Duration> delayAfterPlatformFailure(int retriesSoFar) {
        if (retriesSoFar >= platformRetries || delays.isEmpty()) {
            return Optional.empty();
        }
        return delays.stream().min(Duration::compareTo);
    }

    /** The distinct delays, one retry queue each. */
    public List<Duration> distinctDelays() {
        return delays.stream().distinct().toList();
    }
}
