package com.hireai.domain.biz.task.model;

import com.hireai.utility.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientDeadlineTest {

    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");

    @Test
    void isCountedFromTheGivenInstant() {
        assertThat(ClientDeadline.after(NOW, 600).at()).isEqualTo(Instant.parse("2026-11-10T10:10:00Z"));
    }

    @Test
    void rejectsANonPositiveNumberOfSeconds() {
        assertThatThrownBy(() -> ClientDeadline.after(NOW, 0)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> ClientDeadline.after(NOW, -5)).isInstanceOf(DomainException.class);
    }

    @Test
    void theLongestExecutionLeftIsTheTimeToTheDeadlineLessGrace() {
        ClientDeadline deadline = ClientDeadline.after(NOW, 600);
        assertThat(deadline.longestExecutionSeconds(NOW, 60)).isEqualTo(540);
        assertThat(deadline.longestExecutionSeconds(NOW.plusSeconds(400), 60)).isEqualTo(140);
    }

    @Test
    void aFractionOfASecondLeftIsRoundedDownSoTheStampedDeadlineNeverOverruns() {
        ClientDeadline deadline = ClientDeadline.after(NOW, 600);
        assertThat(deadline.longestExecutionSeconds(NOW.plusMillis(1), 60)).isEqualTo(539);
        assertThat(deadline.longestExecutionSeconds(NOW.plusSeconds(600).plusMillis(1), 0)).isEqualTo(-1);
    }

    @Test
    void anAgentFitsWhenNowPlusItsTimePlusGraceIsNoLaterThanTheDeadline() {
        ClientDeadline deadline = ClientDeadline.after(NOW, 600);
        assertThat(deadline.admits(540, NOW, 60)).isTrue();
        assertThat(deadline.admits(541, NOW, 60)).isFalse();
    }
}
