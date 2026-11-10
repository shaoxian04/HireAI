package com.hireai.infrastructure.messaging;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DispatchRetryScheduleTest {

    private static final List<Duration> PRODUCTION =
            List.of(Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(15));

    @Test
    void aPlatformFailureIsRescheduledOnTheShortestRetryQueueUpToItsBound() {
        DispatchRetrySchedule schedule = new DispatchRetrySchedule(PRODUCTION, 2, 60);

        assertThat(schedule.delayAfterPlatformFailure(0)).contains(Duration.ofSeconds(5));
        assertThat(schedule.delayAfterPlatformFailure(1)).contains(Duration.ofSeconds(5));
        assertThat(schedule.delayAfterPlatformFailure(2)).isEmpty();
        assertThat(new DispatchRetrySchedule(List.of(), 2, 60).delayAfterPlatformFailure(0)).isEmpty();
    }

    @Test
    void threeDelaysMeanFourAttemptsInTotal() {
        DispatchRetrySchedule schedule = new DispatchRetrySchedule(PRODUCTION, 60);

        assertThat(schedule.maxAttempts()).isEqualTo(4);
    }

    @Test
    void eachFailedAttemptWaitsForItsDelayAndTheLastOneHasNone() {
        DispatchRetrySchedule schedule = new DispatchRetrySchedule(PRODUCTION, 60);

        assertThat(schedule.delayAfterFailedAttempt(1)).contains(Duration.ofSeconds(5));
        assertThat(schedule.delayAfterFailedAttempt(2)).contains(Duration.ofSeconds(10));
        assertThat(schedule.delayAfterFailedAttempt(3)).contains(Duration.ofSeconds(15));
        assertThat(schedule.delayAfterFailedAttempt(4)).isEmpty();
    }

    @Test
    void delaysThatAddUpToMoreThanTheExecutionGracePeriodAreRejected() {
        assertThatThrownBy(() -> new DispatchRetrySchedule(PRODUCTION, 29))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("grace");
    }

    @Test
    void delaysThatAddUpToExactlyTheGracePeriodAreAccepted() {
        assertThat(new DispatchRetrySchedule(PRODUCTION, 30).maxAttempts()).isEqualTo(4);
    }

    @Test
    void millisecondDelaysAreAllowedForTests() {
        DispatchRetrySchedule schedule = new DispatchRetrySchedule(List.of(Duration.ofMillis(50)), 1);

        assertThat(schedule.maxAttempts()).isEqualTo(2);
        assertThat(schedule.delayAfterFailedAttempt(1)).contains(Duration.ofMillis(50));
    }

    @Test
    void aZeroOrNegativeDelayIsRejected() {
        assertThatThrownBy(() -> new DispatchRetrySchedule(List.of(Duration.ZERO), 60))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new DispatchRetrySchedule(List.of(Duration.ofSeconds(-1)), 60))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void noDelaysMeansASingleAttempt() {
        DispatchRetrySchedule schedule = new DispatchRetrySchedule(List.of(), 60);

        assertThat(schedule.maxAttempts()).isEqualTo(1);
        assertThat(schedule.delayAfterFailedAttempt(1)).isEmpty();
    }
}
