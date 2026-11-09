package com.hireai.infrastructure.security;

import com.hireai.application.port.security.DispatchTokenClaims;
import com.hireai.utility.exception.DispatchTokenInvalidException;
import com.hireai.infrastructure.security.impl.HmacDispatchTokenService;
import com.hireai.support.clock.MutableTestClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HmacDispatchTokenServiceTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-32b";
    private static final Instant NOW = Instant.parse("2026-11-10T09:00:00Z");

    private final MutableTestClock clock = new MutableTestClock(NOW);
    private final HmacDispatchTokenService service = new HmacDispatchTokenService(SECRET, clock);

    @Test
    void issuesTokenThatVerifiesToTheSameClaims() {
        UUID taskId = UUID.randomUUID();
        UUID agentVersionId = UUID.randomUUID();

        String token = service.issue(taskId, agentVersionId, NOW.plusSeconds(120));
        DispatchTokenClaims claims = service.verify(token);

        assertThat(claims.taskId()).isEqualTo(taskId);
        assertThat(claims.agentVersionId()).isEqualTo(agentVersionId);
    }

    @Test
    void tokenExpiresFiveMinutesAfterTheExecutionDeadline() {
        Instant deadline = NOW.plus(Duration.ofHours(2));

        String token = service.issue(UUID.randomUUID(), UUID.randomUUID(), deadline);

        assertThat(service.verify(token).expiresAt()).isEqualTo(Instant.parse("2026-11-10T11:05:00Z"));
    }

    @Test
    void aLongRunningAgentsTokenIsStillAcceptedAtItsExecutionDeadline() {
        // Regression: the old fixed 15-minute TTL rejected a legitimate 2-hour Agent.
        Instant deadline = NOW.plus(Duration.ofHours(2));
        String token = service.issue(UUID.randomUUID(), UUID.randomUUID(), deadline);

        clock.set(deadline);
        assertThat(service.verify(token).taskId()).isNotNull();

        clock.set(deadline.plus(Duration.ofMinutes(5)));
        assertThat(service.verify(token).taskId()).isNotNull();
    }

    @Test
    void tokenIsRejectedAfterDeadlinePlusFiveMinutes() {
        Instant deadline = NOW.plus(Duration.ofHours(2));
        String token = service.issue(UUID.randomUUID(), UUID.randomUUID(), deadline);

        clock.set(deadline.plus(Duration.ofMinutes(5)).plusSeconds(1));

        assertThatThrownBy(() -> service.verify(token))
                .isInstanceOf(DispatchTokenInvalidException.class);
    }

    @Test
    void rejectsTokenWithTamperedSignature() {
        String token = service.issue(UUID.randomUUID(), UUID.randomUUID(), NOW.plusSeconds(120));
        String tampered = token.substring(0, token.length() - 2)
                + (token.endsWith("AA") ? "BB" : "AA");

        assertThatThrownBy(() -> service.verify(tampered))
                .isInstanceOf(DispatchTokenInvalidException.class);
    }

    @Test
    void rejectsTokenSignedWithADifferentSecret() {
        HmacDispatchTokenService other =
                new HmacDispatchTokenService("OTHER-secret-OTHER-secret-OTHER-secret-32", clock);
        String token = other.issue(UUID.randomUUID(), UUID.randomUUID(), NOW.plusSeconds(120));

        assertThatThrownBy(() -> service.verify(token))
                .isInstanceOf(DispatchTokenInvalidException.class);
    }
}
