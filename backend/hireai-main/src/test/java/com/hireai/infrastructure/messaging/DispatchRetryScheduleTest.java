package com.hireai.infrastructure.messaging;

import com.hireai.domain.biz.task.model.ExecutionGrace;
import com.hireai.infrastructure.client.AgentDispatchTimeouts;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DispatchRetryScheduleTest {

    private static final List<Duration> PRODUCTION =
            List.of(Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(15));
    /** The Agent contract's 5s connect and 15s read: up to 20s per attempt. */
    private static final AgentDispatchTimeouts CONTRACT =
            new AgentDispatchTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(15));
    private static final AgentDispatchTimeouts INSTANT =
            new AgentDispatchTimeouts(Duration.ofMillis(1), Duration.ofMillis(1));

    private static DispatchRetrySchedule schedule(List<Duration> delays, int platformRetries,
                                                  AgentDispatchTimeouts timeouts, long graceSeconds) {
        return new DispatchRetrySchedule(delays, platformRetries, timeouts, new ExecutionGrace(graceSeconds));
    }

    @Test
    void theWorstCaseCountsEveryAttemptsTimeoutsTheDelaysAndThePlatformReschedules() {
        // 4 attempts x 20s + (5 + 10 + 15)s + 2 x 5s = 120s.
        assertThat(schedule(PRODUCTION, 2, CONTRACT, 120).maxAttempts()).isEqualTo(4);
        assertThatThrownBy(() -> schedule(PRODUCTION, 2, CONTRACT, 119))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("grace");
    }

    @Test
    void delaysAloneFittingTheGraceIsNotEnoughWhenTheSendsCanRunToTheirTimeouts() {
        // The delays add up to 30s, well inside 60s, but four 20s sends don't fit with them.
        assertThatThrownBy(() -> schedule(PRODUCTION, 0, CONTRACT, 60))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("connect+read");
    }

    @Test
    void theShippedDefaultsFitTheShippedGrace() {
        // application.yml: retry-delays 5s,10s,15s, platform-retries 2, 5s/15s timeouts, grace 120.
        assertThat(schedule(PRODUCTION, 2, CONTRACT, 120).maxAttempts()).isEqualTo(4);
    }

    @Test
    void aPlatformFailureIsRescheduledOnTheShortestRetryQueueUpToItsBound() {
        DispatchRetrySchedule schedule = schedule(PRODUCTION, 2, CONTRACT, 120);

        assertThat(schedule.delayAfterPlatformFailure(0)).contains(Duration.ofSeconds(5));
        assertThat(schedule.delayAfterPlatformFailure(1)).contains(Duration.ofSeconds(5));
        assertThat(schedule.delayAfterPlatformFailure(2)).isEmpty();
        assertThat(schedule(List.of(), 2, INSTANT, 60).delayAfterPlatformFailure(0)).isEmpty();
    }

    @Test
    void threeDelaysMeanFourAttemptsInTotal() {
        assertThat(schedule(PRODUCTION, 0, INSTANT, 60).maxAttempts()).isEqualTo(4);
    }

    @Test
    void eachFailedAttemptWaitsForItsDelayAndTheLastOneHasNone() {
        DispatchRetrySchedule schedule = schedule(PRODUCTION, 0, INSTANT, 60);

        assertThat(schedule.delayAfterFailedAttempt(1)).contains(Duration.ofSeconds(5));
        assertThat(schedule.delayAfterFailedAttempt(2)).contains(Duration.ofSeconds(10));
        assertThat(schedule.delayAfterFailedAttempt(3)).contains(Duration.ofSeconds(15));
        assertThat(schedule.delayAfterFailedAttempt(4)).isEmpty();
    }

    @Test
    void millisecondDelaysAreAllowedForTests() {
        DispatchRetrySchedule schedule = schedule(List.of(Duration.ofMillis(50)), 0, INSTANT, 1);

        assertThat(schedule.maxAttempts()).isEqualTo(2);
        assertThat(schedule.delayAfterFailedAttempt(1)).contains(Duration.ofMillis(50));
    }

    @Test
    void aZeroOrNegativeDelayIsRejected() {
        assertThatThrownBy(() -> schedule(List.of(Duration.ZERO), 0, INSTANT, 60))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> schedule(List.of(Duration.ofSeconds(-1)), 0, INSTANT, 60))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aNegativePlatformRetryBoundIsRejected() {
        assertThatThrownBy(() -> schedule(PRODUCTION, -1, INSTANT, 60))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void noDelaysMeansASingleAttempt() {
        DispatchRetrySchedule schedule = schedule(List.of(), 0, INSTANT, 60);

        assertThat(schedule.maxAttempts()).isEqualTo(1);
        assertThat(schedule.delayAfterFailedAttempt(1)).isEmpty();
    }
}
