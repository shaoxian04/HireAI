package com.hireai.infrastructure.security;

import com.hireai.application.port.security.DispatchTokenClaims;
import com.hireai.utility.exception.DispatchTokenInvalidException;
import com.hireai.infrastructure.security.impl.HmacDispatchTokenService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HmacDispatchTokenServiceTest {

    private final HmacDispatchTokenService service =
            new HmacDispatchTokenService("test-secret-test-secret-test-secret-32b");

    @Test
    void issuesTokenThatVerifiesToTheSameClaims() {
        UUID taskId = UUID.randomUUID();
        UUID agentVersionId = UUID.randomUUID();

        String token = service.issue(taskId, agentVersionId, Duration.ofMinutes(5));
        DispatchTokenClaims claims = service.verify(token);

        assertThat(claims.taskId()).isEqualTo(taskId);
        assertThat(claims.agentVersionId()).isEqualTo(agentVersionId);
        assertThat(claims.expiresAt()).isAfter(java.time.Instant.now());
    }

    @Test
    void rejectsTokenWithTamperedSignature() {
        String token = service.issue(UUID.randomUUID(), UUID.randomUUID(), Duration.ofMinutes(5));
        String tampered = token.substring(0, token.length() - 2)
                + (token.endsWith("AA") ? "BB" : "AA");

        assertThatThrownBy(() -> service.verify(tampered))
                .isInstanceOf(DispatchTokenInvalidException.class);
    }

    @Test
    void rejectsTokenSignedWithADifferentSecret() {
        HmacDispatchTokenService other =
                new HmacDispatchTokenService("OTHER-secret-OTHER-secret-OTHER-secret-32");
        String token = other.issue(UUID.randomUUID(), UUID.randomUUID(), Duration.ofMinutes(5));

        assertThatThrownBy(() -> service.verify(token))
                .isInstanceOf(DispatchTokenInvalidException.class);
    }

    @Test
    void rejectsExpiredToken() {
        String token = service.issue(UUID.randomUUID(), UUID.randomUUID(), Duration.ofSeconds(-1));

        assertThatThrownBy(() -> service.verify(token))
                .isInstanceOf(DispatchTokenInvalidException.class);
    }
}
