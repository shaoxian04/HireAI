package com.hireai.webhook;

import com.hireai.domain.biz.webhook.WebhookBackoffPolicy;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class WebhookBackoffPolicyTest {
    private final WebhookBackoffPolicy p = new WebhookBackoffPolicy(10, 3600, 28);
    private final Instant now = Instant.parse("2026-07-19T00:00:00Z");

    @Test void firstFailureWaitsBase() {
        assertThat(p.nextAttempt(1, now)).isEqualTo(now.plusSeconds(10));
    }
    @Test void backoffIsExponential() {
        assertThat(p.nextAttempt(2, now)).isEqualTo(now.plusSeconds(20));
        assertThat(p.nextAttempt(3, now)).isEqualTo(now.plusSeconds(40));
    }
    @Test void backoffIsCappedAndDoesNotOverflow() {
        assertThat(p.nextAttempt(9, now)).isEqualTo(now.plusSeconds(2560));
        assertThat(p.nextAttempt(10, now)).isEqualTo(now.plusSeconds(3600)); // capped
        assertThat(p.nextAttempt(100, now)).isEqualTo(now.plusSeconds(3600)); // no shift overflow
    }
    @Test void overflowGuardClampsUnmaskedShiftToCap() {
        // Genuinely exercises the `shift >= 20` guard: shift 60/63 are unmasked (< 64), so without the
        // guard `baseSeconds << shift` overflows to a negative/zero delay. (The attempts=100 case above
        // masks down to shift 35 and stays safe even without the guard, so it does not prove it.)
        assertThat(p.nextAttempt(61, now)).isEqualTo(now.plusSeconds(3600)); // shift 60: 10L<<60 wraps negative
        assertThat(p.nextAttempt(64, now)).isEqualTo(now.plusSeconds(3600)); // shift 63: 10L<<63 == 0
    }
    @Test void exhaustedAtMaxAttempts() {
        assertThat(p.exhausted(27)).isFalse();
        assertThat(p.exhausted(28)).isTrue();
    }
}
