package com.hireai.infrastructure.messaging;

import com.hireai.domain.biz.task.model.ExecutionGrace;
import com.hireai.infrastructure.client.AgentDispatchTimeouts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * How often, and how long apart, a failed WorkOrder send is retried. The configured delays
 * ({@code hireai.dispatch.retry-delays}) are the waits between attempts, so N delays give N + 1
 * attempts in total. Startup fails if a delay is not positive, or if the dispatch path's worst case
 * does not fit in the execution grace period ({@link ExecutionGrace}): every attempt running to its
 * connect + read timeout ({@link AgentDispatchTimeouts}), plus every delay, plus every platform-side
 * re-schedule. The grace is added to every execution deadline, so retrying can then never eat into
 * the Agent's declared execution time.
 */
@Component
public class DispatchRetrySchedule {

    private final List<Duration> delays;
    private final int platformRetries;

    public DispatchRetrySchedule(
            @Value("${hireai.dispatch.retry-delays}") List<Duration> delays,
            @Value("${hireai.dispatch.platform-retries}") int platformRetries,
            AgentDispatchTimeouts timeouts,
            ExecutionGrace grace) {
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
        Duration sends = timeouts.perAttempt().multipliedBy(maxAttempts());
        Duration reschedules = delays.isEmpty() ? Duration.ZERO
                : this.delays.stream().min(Duration::compareTo).orElseThrow().multipliedBy(platformRetries);
        Duration worstCase = sends.plus(total).plus(reschedules);
        Duration graceDuration = Duration.ofSeconds(grace.seconds());
        if (worstCase.compareTo(graceDuration) > 0) {
            throw new IllegalStateException("The dispatch path can take up to " + worstCase + " ("
                    + maxAttempts() + " attempts x " + timeouts.perAttempt() + " connect+read timeout, "
                    + total + " of hireai.dispatch.retry-delays, " + reschedules
                    + " of platform re-schedules), more than the execution grace period "
                    + "hireai.execution.grace-seconds (" + graceDuration + ")");
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
